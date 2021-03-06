package li.cil.oc.server.component.machine

import java.io
import java.lang.reflect.Constructor
import java.util.concurrent.{Callable, TimeUnit}
import java.util.logging.Level

import li.cil.oc.api.detail.MachineAPI
import li.cil.oc.api.machine.{Architecture, ExecutionResult, LimitReachedException, Owner}
import li.cil.oc.api.network._
import li.cil.oc.api.{FileSystem, Network, fs, machine}
import li.cil.oc.common.tileentity
import li.cil.oc.server.PacketSender
import li.cil.oc.server.component.ManagedComponent
import li.cil.oc.server.fs.CompositeReadOnlyFileSystem
import li.cil.oc.util.ExtendedNBT._
import li.cil.oc.util.ThreadPoolFactory
import li.cil.oc.{OpenComputers, Settings, server}
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt._
import net.minecraft.server.MinecraftServer
import net.minecraft.server.integrated.IntegratedServer
import net.minecraftforge.common.DimensionManager

import scala.Array.canBuildFrom
import scala.collection.mutable

class Machine(val owner: Owner, val rom: Option[ManagedEnvironment], constructor: Constructor[_ <: Architecture]) extends ManagedComponent with machine.Machine with Runnable {
  val node = Network.newNode(this, Visibility.Network).
    withComponent("computer", Visibility.Neighbors).
    withConnector(Settings.get.bufferComputer).
    create()

  val tmp = if (Settings.get.tmpSize > 0) {
    Option(FileSystem.asManagedEnvironment(FileSystem.
      fromMemory(Settings.get.tmpSize * 1024), "tmpfs"))
  } else None

  val architecture = constructor.newInstance(this)

  private[component] val state = mutable.Stack(Machine.State.Stopped)

  private val _components = mutable.Map.empty[String, String]

  private val addedComponents = mutable.Set.empty[Component]

  private val _users = mutable.Set.empty[String]

  private val signals = mutable.Queue.empty[Machine.Signal]

  private val callCounts = mutable.Map.empty[String, mutable.Map[String, Int]]

  // ----------------------------------------------------------------------- //

  private var timeStarted = 0L // Game-world time [ms] for os.uptime().

  var worldTime = 0L // Game-world time for os.time().

  private var cpuTotal = 0L // Pseudo-real-world time [ns] for os.clock().

  private var cpuStart = 0L // Pseudo-real-world time [ns] for os.clock().

  private var remainIdle = 0 // Ticks left to sleep before resuming.

  private var remainingPause = 0 // Ticks left to wait before resuming.

  private var usersChanged = false // Send updated users list to clients?

  private var message: Option[String] = None // For error messages.

  private var cost = Settings.get.computerCost * Settings.get.tickFrequency

  // ----------------------------------------------------------------------- //

  override def components = scala.collection.convert.WrapAsJava.mapAsJavaMap(_components)

  def componentCount = _components.count {
    case (_, name) => name != "filesystem"
  } + addedComponents.count(_.name != "filesystem") - 1 // -1 = this computer

  override def romAddress = rom.fold(null: String)(_.node.address)

  override def tmpAddress = tmp.fold(null: String)(_.node.address)

  def lastError = message.orNull

  override def setCostPerTick(value: Double) = cost = value * Settings.get.tickFrequency

  override def getCostPerTick = cost / Settings.get.tickFrequency

  override def users = _users.synchronized(_users.toArray)

  override def upTime() = (worldTime - timeStarted) / 20.0

  override def cpuTime = (cpuTotal + (System.nanoTime() - cpuStart)) * 10e-10

  // ----------------------------------------------------------------------- //

  override def address = node.address

  override def canInteract(player: String) = !Settings.get.canComputersBeOwned ||
    _users.synchronized(_users.isEmpty || _users.contains(player)) ||
    MinecraftServer.getServer.isSinglePlayer ||
    MinecraftServer.getServer.getConfigurationManager.isPlayerOpped(player)

  override def isRunning = state.synchronized(state.top != Machine.State.Stopped && state.top != Machine.State.Stopping)

  override def isPaused = state.synchronized(state.top == Machine.State.Paused && remainingPause > 0)

  override def start() = state.synchronized(state.top match {
    case Machine.State.Stopped =>
      processAddedComponents()
      verifyComponents()
      val rules = owner.world.getWorldInfo.getGameRulesInstance
      if (rules.hasRule("doDaylightCycle") && !rules.getGameRuleBooleanValue("doDaylightCycle")) {
        crash(Settings.namespace + "gui.Error.DaylightCycle")
        false
      }
      else if (componentCount > owner.maxComponents) {
        crash(Settings.namespace + (owner match {
          case t: tileentity.Case if !t.hasCPU => "gui.Error.NoCPU"
          case s: server.component.Server if !s.hasCPU => "gui.Error.NoCPU"
          case _ => "gui.Error.ComponentOverflow"
        }))
        false
      }
      else if (owner.installedMemory > 0) {
        if (Settings.get.ignorePower || node.globalBuffer > cost) {
          init() && {
            switchTo(Machine.State.Starting)
            timeStarted = owner.world.getWorldTime
            node.sendToReachable("computer.started")
            true
          }
        }
        else {
          crash(Settings.namespace + "gui.Error.NoEnergy")
          false
        }
      }
      else {
        crash(Settings.namespace + "gui.Error.NoRAM")
        false
      }
    case Machine.State.Paused if remainingPause > 0 =>
      remainingPause = 0
      owner.markAsChanged()
      true
    case Machine.State.Stopping =>
      switchTo(Machine.State.Restarting)
      true
    case _ =>
      false
  })

  override def pause(seconds: Double): Boolean = {
    val ticksToPause = math.max((seconds * 20).toInt, 0)
    def shouldPause(state: Machine.State.Value) = state match {
      case Machine.State.Stopping | Machine.State.Stopped => false
      case Machine.State.Paused if ticksToPause <= remainingPause => false
      case _ => true
    }
    if (shouldPause(state.synchronized(state.top))) {
      // Check again when we get the lock, might have changed since.
      Machine.this.synchronized(state.synchronized(if (shouldPause(state.top)) {
        if (state.top != Machine.State.Paused) {
          assert(!state.contains(Machine.State.Paused))
          state.push(Machine.State.Paused)
        }
        remainingPause = ticksToPause
        owner.markAsChanged()
        return true
      }))
    }
    false
  }

  override def stop() = state.synchronized(state.top match {
    case Machine.State.Stopped | Machine.State.Stopping =>
      false
    case _ =>
      state.push(Machine.State.Stopping)
      true
  })

  override def crash(message: String) = {
    this.message = Option(message)
    stop()
  }

  override def signal(name: String, args: AnyRef*) = state.synchronized(state.top match {
    case Machine.State.Stopped | Machine.State.Stopping => false
    case _ => signals.synchronized {
      if (signals.size >= 256) false
      else if (args == null) {
        signals.enqueue(new Machine.Signal(name, Array.empty))
        true
      }
      else {
        signals.enqueue(new Machine.Signal(name, args.map {
          case null | Unit | None => null
          case arg: java.lang.Boolean => arg
          case arg: java.lang.Byte => Double.box(arg.doubleValue)
          case arg: java.lang.Character => Double.box(arg.toDouble)
          case arg: java.lang.Short => Double.box(arg.doubleValue)
          case arg: java.lang.Integer => Double.box(arg.doubleValue)
          case arg: java.lang.Long => Double.box(arg.doubleValue)
          case arg: java.lang.Float => Double.box(arg.doubleValue)
          case arg: java.lang.Double => arg
          case arg: java.lang.String => arg
          case arg: Array[Byte] => arg
          case arg: Map[_, _] if arg.isEmpty || arg.head._1.isInstanceOf[String] && arg.head._2.isInstanceOf[String] => arg
          case arg =>
            OpenComputers.log.warning("Trying to push signal with an unsupported argument of type " + arg.getClass.getName)
            null
        }.toArray[AnyRef]))
        true
      }
    }
  })

  override def popSignal(): Machine.Signal = signals.synchronized(if (signals.isEmpty) null else signals.dequeue())

  override def invoke(address: String, method: String, args: Array[AnyRef]) =
    Option(node.network.node(address)) match {
      case Some(component: server.network.Component) if component.canBeSeenFrom(node) || component == node =>
        val direct = component.isDirect(method)
        if (direct && architecture.isInitialized) callCounts.synchronized {
          val limit = component.limit(method)
          val counts = callCounts.getOrElseUpdate(component.address, mutable.Map.empty[String, Int])
          val count = counts.getOrElseUpdate(method, 0)
          if (count >= limit) {
            throw new LimitReachedException()
          }
          counts(method) += 1
        }
        component.invoke(method, this, args: _*)
      case _ => throw new IllegalArgumentException("no such component")
    }

  override def documentation(address: String, method: String) = Option(node.network.node(address)) match {
    case Some(component: server.network.Component) if component.canBeSeenFrom(node) || component == node => component.doc(method)
    case _ => throw new IllegalArgumentException("no such component")
  }

  override def addUser(name: String) {
    if (_users.size >= Settings.get.maxUsers)
      throw new Exception("too many users")

    if (_users.contains(name))
      throw new Exception("user exists")
    if (name.length > Settings.get.maxUsernameLength)
      throw new Exception("username too long")
    if (!MinecraftServer.getServer.getConfigurationManager.getAllUsernames.contains(name))
      throw new Exception("player must be online")

    _users.synchronized {
      _users += name
      usersChanged = true
    }
  }

  override def removeUser(name: String) = _users.synchronized {
    val success = _users.remove(name)
    if (success) {
      usersChanged = true
    }
    success
  }

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function():boolean -- Starts the computer. Returns true if the state changed.""")
  def start(context: Context, args: Arguments): Array[AnyRef] =
    result(!isPaused && start())

  @Callback(doc = """function():boolean -- Stops the computer. Returns true if the state changed.""")
  def stop(context: Context, args: Arguments): Array[AnyRef] =
    result(stop())

  @Callback(direct = true, doc = """function():boolean -- Returns whether the computer is running.""")
  def isRunning(context: Context, args: Arguments): Array[AnyRef] =
    result(isRunning)

  @Callback(doc = """function([frequency:number[, duration:number]]) -- Plays a tone, useful to alert users via audible feedback.""")
  def beep(context: Context, args: Arguments): Array[AnyRef] = {
    val frequency = if (args.count() > 0) args.checkInteger(0) else 440
    if (frequency < 20 || frequency > 2000) {
      throw new IllegalArgumentException("invalid frequency, must be in [20, 2000]")
    }
    val duration = if (args.count() > 1) args.checkDouble(1) else 0.1
    val durationInMilliseconds = math.max(50, math.min(5000, (duration * 1000).toInt))
    context.pause(durationInMilliseconds / 1000.0)
    PacketSender.sendSound(owner.world, owner.x, owner.y, owner.z, frequency, durationInMilliseconds)
    null
  }

  // ----------------------------------------------------------------------- //

  override val canUpdate = true

  override def update() = if (state.synchronized(state.top != Machine.State.Stopped)) {
    // Add components that were added since the last update to the actual list
    // of components if we can see them. We use this delayed approach to avoid
    // issues with components that have a visibility lower than their
    // reachability, because in that case if they get connected in the wrong
    // order we wouldn't add them (since they'd be invisible in their connect
    // message, and only become visible with a later node-to-node connection,
    // but that wouldn't trigger a connect message anymore due to the higher
    // reachability).
    processAddedComponents()

    // Component overflow check, crash if too many components are connected, to
    // avoid confusion on the user's side due to components not showing up.
    if (componentCount > owner.maxComponents) {
      crash(Settings.namespace + "gui.Error.ComponentOverflow")
    }

    // Update world time for time().
    worldTime = owner.world.getWorldTime

    // We can have rollbacks from '/time set'. Avoid getting negative uptimes.
    timeStarted = math.min(timeStarted, worldTime)

    if (remainIdle > 0) {
      remainIdle -= 1
    }

    // Reset direct call limits.
    callCounts.synchronized(if (callCounts.size > 0) callCounts.clear())

    // Make sure we have enough power.
    if (worldTime % Settings.get.tickFrequency == 0) {
      state.synchronized(state.top match {
        case Machine.State.Paused |
             Machine.State.Restarting |
             Machine.State.Stopping |
             Machine.State.Stopped => // No power consumption.
        case Machine.State.Sleeping if remainIdle > 0 && signals.isEmpty =>
          if (!node.tryChangeBuffer(-cost * Settings.get.sleepCostFactor)) {
            crash(Settings.namespace + "gui.Error.NoEnergy")
          }
        case _ =>
          if (!node.tryChangeBuffer(-cost)) {
            crash(Settings.namespace + "gui.Error.NoEnergy")
          }
      })
    }

    // Avoid spamming user list across the network.
    if (worldTime % 20 == 0 && usersChanged) {
      val list = _users.synchronized {
        usersChanged = false
        users
      }
      owner match {
        case computer: tileentity.traits.Computer => PacketSender.sendComputerUserList(computer, list)
        case _ =>
      }
    }

    // Check if we should switch states. These are all the states in which we're
    // guaranteed that the executor thread isn't running anymore.
    state.synchronized(state.top match {
      // Booting up.
      case Machine.State.Starting =>
        verifyComponents()
        switchTo(Machine.State.Yielded)
      // Computer is rebooting.
      case Machine.State.Restarting =>
        close()
        if (Settings.get.eraseTmpOnReboot) {
          tmp.foreach(_.node.remove()) // To force deleting contents.
          tmp.foreach(tmp => node.connect(tmp.node))
        }
        node.sendToReachable("computer.stopped")
        start()
      // Resume from pauses based on sleep or signal underflow.
      case Machine.State.Sleeping if remainIdle <= 0 || !signals.isEmpty =>
        switchTo(Machine.State.Yielded)
      // Resume in case we paused  because the game was paused.
      case Machine.State.Paused =>
        if (remainingPause > 0) {
          remainingPause -= 1
        }
        else {
          verifyComponents() // In case we're resuming after loading.
          state.pop()
          switchTo(state.top) // Trigger execution if necessary.
        }
      // Perform a synchronized call (message sending).
      case Machine.State.SynchronizedCall =>
        // Clear direct call limits again, just to be on the safe side...
        // Theoretically it'd be possible for the executor to do some direct
        // calls between the clear and the state check, which could in turn
        // make this synchronized call fail due the limit still being maxed.
        callCounts.clear()
        // We switch into running state, since we'll behave as though the call
        // were performed from our executor thread.
        switchTo(Machine.State.Running)
        try {
          architecture.runSynchronized()
          // Check if the callback called pause() or stop().
          state.top match {
            case Machine.State.Running =>
              switchTo(Machine.State.SynchronizedReturn)
            case Machine.State.Paused =>
              state.pop() // Paused
              state.pop() // Running, no switchTo to avoid new future.
              state.push(Machine.State.SynchronizedReturn)
              state.push(Machine.State.Paused)
            case Machine.State.Stopping => // Nothing to do, we'll die anyway.
            case _ => throw new AssertionError()
          }
        }
        catch {
          case e: java.lang.Error if e.getMessage == "not enough memory" =>
            crash(Settings.namespace + "gui.Error.OutOfMemory")
          case e: Throwable =>
            OpenComputers.log.log(Level.WARNING, "Faulty architecture implementation for synchronized calls.", e)
            crash(Settings.namespace + "gui.Error.InternalError")
        }

        assert(state.top != Machine.State.Running)
      case _ => // Nothing special to do, just avoid match errors.
    })

    // Finally check if we should stop the computer. We cannot lock the state
    // because we may have to wait for the executor thread to finish, which
    // might turn into a deadlock depending on where it currently is.
    state.synchronized(state.top) match {
      // Computer is shutting down.
      case Machine.State.Stopping => Machine.this.synchronized(state.synchronized {
        close()
        tmp.foreach(_.node.remove()) // To force deleting contents.
        if (node.network != null) {
          tmp.foreach(tmp => node.connect(tmp.node))
        }
        node.sendToReachable("computer.stopped")
      })
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  override def onMessage(message: Message) {
    message.data match {
      case Array(name: String, args@_*) if message.name == "computer.signal" =>
        signal(name, Seq(message.source.address) ++ args: _*)
      case Array(player: EntityPlayer, name: String, args@_*) if message.name == "computer.checked_signal" =>
        if (canInteract(player.getCommandSenderName))
          signal(name, Seq(message.source.address) ++ args: _*)
      case _ =>
    }
  }

  override def onConnect(node: Node) {
    if (node == this.node) {
      _components += this.node.address -> this.node.name
      rom.foreach(fs => node.connect(fs.node))
      tmp.foreach(fs => node.connect(fs.node))
      architecture.onConnect()
    }
    else {
      node match {
        case component: Component => addComponent(component)
        case _ =>
      }
    }
    // For computers, to generate the components in their inventory.
    owner.onMachineConnect(node)
  }

  override def onDisconnect(node: Node) {
    if (node == this.node) {
      close()
      rom.foreach(_.node.remove())
      tmp.foreach(_.node.remove())
    }
    else {
      node match {
        case component: Component => removeComponent(component)
        case _ =>
      }
    }
    // For computers, to save the components in their inventory.
    owner.onMachineDisconnect(node)
  }

  // ----------------------------------------------------------------------- //

  def addComponent(component: Component) {
    if (!_components.contains(component.address)) {
      addedComponents += component
    }
  }

  def removeComponent(component: Component) {
    if (_components.contains(component.address)) {
      _components.synchronized(_components -= component.address)
      signal("component_removed", component.address, component.name)
    }
    addedComponents -= component
  }

  private def processAddedComponents() {
    if (addedComponents.size > 0) {
      for (component <- addedComponents) {
        if (component.canBeSeenFrom(node)) {
          _components.synchronized(_components += component.address -> component.name)
          // Skip the signal if we're not initialized yet, since we'd generate a
          // duplicate in the startup script otherwise.
          if (architecture.isInitialized) {
            signal("component_added", component.address, component.name)
          }
        }
      }
      addedComponents.clear()
    }
  }

  private def verifyComponents() {
    val invalid = mutable.Set.empty[String]
    for ((address, name) <- _components) {
      if (node.network.node(address) == null) {
        if (name == "filesystem") {
          OpenComputers.log.fine(s"A component of type '$name' disappeared ($address)! This usually means that it didn't save its node.")
          OpenComputers.log.fine("If this was a file system provided by a ComputerCraft peripheral, this is normal.")
        }
        else OpenComputers.log.warning(s"A component of type '$name' disappeared ($address)! This usually means that it didn't save its node.")
        signal("component_removed", address, name)
        invalid += address
      }
    }
    for (address <- invalid) {
      _components -= address
    }
  }

  // ----------------------------------------------------------------------- //

  override def load(nbt: NBTTagCompound) = Machine.this.synchronized {
    assert(state.top == Machine.State.Stopped)
    assert(_users.isEmpty)
    assert(signals.isEmpty)
    state.clear()

    super.load(nbt)

    state.pushAll(nbt.getTagList("state").iterator[NBTTagInt].reverse.map(s => Machine.State(s.data)))
    nbt.getTagList("users").foreach[NBTTagString](u => _users += u.data)
    if (nbt.hasKey("message")) {
      message = Some(nbt.getString("message"))
    }

    _components ++= nbt.getTagList("components").iterator[NBTTagCompound].map(c =>
      c.getString("address") -> c.getString("name"))

    rom.foreach(fs => fs.load(nbt.getCompoundTag("rom")))
    tmp.foreach(fs => fs.load(nbt.getCompoundTag("tmp")))

    if (state.size > 0 && state.top != Machine.State.Stopped && init()) {
      architecture.load(nbt)

      signals ++= nbt.getTagList("signals").iterator[NBTTagCompound].map(signalNbt => {
        val argsNbt = signalNbt.getCompoundTag("args")
        val argsLength = argsNbt.getInteger("length")
        new Machine.Signal(signalNbt.getString("name"),
          (0 until argsLength).map("arg" + _).map(argsNbt.getTag).map {
            case tag: NBTTagByte if tag.data == -1 => null
            case tag: NBTTagByte => Boolean.box(tag.data == 1)
            case tag: NBTTagDouble => Double.box(tag.data)
            case tag: NBTTagString => tag.data
            case tag: NBTTagByteArray => tag.byteArray
            case tag: NBTTagList =>
              val data = mutable.Map.empty[String, String]
              for (i <- 0 until tag.tagCount by 2) {
                (tag.tagAt(i), tag.tagAt(i + 1)) match {
                  case (key: NBTTagString, value: NBTTagString) => data += key.data -> value.data
                  case _ =>
                }
              }
              data
            case _ => null
          }.toArray[AnyRef])
      })

      timeStarted = nbt.getLong("timeStarted")
      cpuTotal = nbt.getLong("cpuTime")
      remainingPause = nbt.getInteger("remainingPause")

      // Delay execution for a second to allow the world around us to settle.
      if (state.top != Machine.State.Restarting) {
        pause(Settings.get.startupDelay)
      }
    }
    else close() // Clean up in case we got a weird state stack.
  }

  override def save(nbt: NBTTagCompound): Unit = Machine.this.synchronized {
    assert(state.top != Machine.State.Running) // Lock on 'this' should guarantee this.

    // Make sure we don't continue running until everything has saved.
    pause(0.05)

    super.save(nbt)

    // Make sure the component list is up-to-date.
    processAddedComponents()

    nbt.setNewTagList("state", state.map(_.id))
    nbt.setNewTagList("users", _users)
    message.foreach(nbt.setString("message", _))

    val componentsNbt = new NBTTagList()
    for ((address, name) <- _components) {
      val componentNbt = new NBTTagCompound()
      componentNbt.setString("address", address)
      componentNbt.setString("name", name)
      componentsNbt.appendTag(componentNbt)
    }
    nbt.setTag("components", componentsNbt)

    rom.foreach(fs => nbt.setNewCompoundTag("rom", fs.save))
    tmp.foreach(fs => nbt.setNewCompoundTag("tmp", fs.save))

    if (state.top != Machine.State.Stopped) {
      architecture.save(nbt)

      val signalsNbt = new NBTTagList()
      for (s <- signals.iterator) {
        val signalNbt = new NBTTagCompound()
        signalNbt.setString("name", s.name)
        signalNbt.setNewCompoundTag("args", args => {
          args.setInteger("length", s.args.length)
          s.args.zipWithIndex.foreach {
            case (null, i) => args.setByte("arg" + i, -1)
            case (arg: java.lang.Boolean, i) => args.setByte("arg" + i, if (arg) 1 else 0)
            case (arg: java.lang.Double, i) => args.setDouble("arg" + i, arg)
            case (arg: String, i) => args.setString("arg" + i, arg)
            case (arg: Array[Byte], i) => args.setByteArray("arg" + i, arg)
            case (arg: Map[_, _], i) =>
              val list = new NBTTagList()
              for ((key, value) <- arg) {
                list.append(key.toString)
                list.append(value.toString)
              }
              args.setTag("arg" + i, list)
            case (_, i) => args.setByte("arg" + i, -1)
          }
        })
        signalsNbt.appendTag(signalNbt)
      }
      nbt.setTag("signals", signalsNbt)

      nbt.setLong("timeStarted", timeStarted)
      nbt.setLong("cpuTime", cpuTotal)
      nbt.setInteger("remainingPause", remainingPause)
    }
  }

  // ----------------------------------------------------------------------- //

  private def init(): Boolean = {
    // Reset error state.
    message = None

    // Clear any left-over signals from a previous run.
    signals.clear()

    // Connect the ROM and `/tmp` node to our owner. We're not in a network in
    // case we're loading, which is why we have to check it here.
    if (node.network != null) {
      tmp.foreach(fs => node.connect(fs.node))
      rom.foreach(fs => node.connect(fs.node))
    }

    try {
      return architecture.initialize()
    }
    catch {
      case ex: Throwable =>
        OpenComputers.log.log(Level.WARNING, "Failed initializing computer.", ex)
        close()
    }
    false
  }

  private def close() = state.synchronized(
    if (state.size == 0 || state.top != Machine.State.Stopped) {
      state.clear()
      state.push(Machine.State.Stopped)
      architecture.close()
      signals.clear()
      timeStarted = 0
      cpuTotal = 0
      cpuStart = 0
      remainIdle = 0

      // Mark state change in owner, to send it to clients.
      owner.markAsChanged()
    })

  // ----------------------------------------------------------------------- //

  private def switchTo(value: Machine.State.Value) = {
    val result = state.pop()
    state.push(value)
    if (value == Machine.State.Yielded || value == Machine.State.SynchronizedReturn) {
      remainIdle = 0
      Machine.threadPool.schedule(this, Settings.get.executionDelay, TimeUnit.MILLISECONDS)
    }

    // Mark state change in owner, to send it to clients.
    owner.markAsChanged()

    result
  }

  private def isGamePaused = !MinecraftServer.getServer.isDedicatedServer && (MinecraftServer.getServer match {
    case integrated: IntegratedServer => integrated.getServerListeningThread.isGamePaused
    case _ => false
  })

  // This is a really high level lock that we only use for saving and loading.
  override def run(): Unit = Machine.this.synchronized {
    val isSynchronizedReturn = state.synchronized {
      if (state.top != Machine.State.Yielded &&
        state.top != Machine.State.SynchronizedReturn) {
        return
      }
      // See if the game appears to be paused, in which case we also pause.
      if (isGamePaused) {
        state.push(Machine.State.Paused)
        return
      }
      switchTo(Machine.State.Running) == Machine.State.SynchronizedReturn
    }

    cpuStart = System.nanoTime()

    try {
      val result = architecture.runThreaded(isSynchronizedReturn)

      // Check if someone called pause() or stop() in the meantime.
      state.synchronized {
        state.top match {
          case Machine.State.Running =>
            result match {
              case result: ExecutionResult.Sleep =>
                signals.synchronized {
                  // Immediately check for signals to allow processing more than one
                  // signal per game tick.
                  if (signals.isEmpty && result.ticks > 0) {
                    switchTo(Machine.State.Sleeping)
                    remainIdle = result.ticks
                  } else {
                    switchTo(Machine.State.Yielded)
                  }
                }
              case result: ExecutionResult.SynchronizedCall =>
                switchTo(Machine.State.SynchronizedCall)
              case result: ExecutionResult.Shutdown =>
                if (result.reboot) {
                  switchTo(Machine.State.Restarting)
                }
                else {
                  switchTo(Machine.State.Stopping)
                }
              case result: ExecutionResult.Error =>
                crash(result.message)
            }
          case Machine.State.Paused =>
            state.pop() // Paused
            state.pop() // Running, no switchTo to avoid new future.
            state.push(Machine.State.Yielded)
            state.push(Machine.State.Paused)
          case Machine.State.Stopping => // Nothing to do, we'll die anyway.
          case _ => throw new AssertionError("Invalid state in executor post-processing.")
        }
        assert(state.top != Machine.State.Running)
      }
    }
    catch {
      case e: Throwable =>
        OpenComputers.log.log(Level.WARNING, "Architecture's runThreaded threw an error. This should never happen!", e)
        crash(Settings.namespace + "gui.Error.InternalError")
    }

    // Keep track of time spent executing the computer.
    cpuTotal += System.nanoTime() - cpuStart
  }
}

object Machine extends MachineAPI {
  val checked = mutable.Set.empty[Class[_ <: Architecture]]

  var roms = mutable.Map.empty[Class[_ <: Architecture], mutable.LinkedHashMap[String, Callable[fs.FileSystem]]]

  override def add(architecture: Class[_ <: Architecture]) {
    if (!checked.contains(architecture)) {
      try {
        architecture.getConstructor(classOf[machine.Machine])
      }
      catch {
        case t: Throwable => throw new IllegalArgumentException("Architecture does not have required constructor.")
      }
      checked += architecture
      roms += architecture -> mutable.LinkedHashMap.empty[String, Callable[fs.FileSystem]]
    }
  }

  override def addRomResource(architecture: Class[_ <: Architecture], resource: Callable[fs.FileSystem], name: String) {
    add(architecture)
    val rom = roms(architecture)
    if (rom.contains(name)) {
      throw new IllegalArgumentException(s"A file system with the name '$name' is already registered.")
    }
    rom += name -> resource
  }

  override def architectures() = scala.collection.convert.WrapAsJava.asJavaIterable(checked)

  override def create(owner: Owner, architecture: Class[_ <: Architecture]) = {
    add(architecture)
    val rom = new CompositeReadOnlyFileSystem(roms(architecture))
    val instance = new Machine(owner,
      Option(FileSystem.asManagedEnvironment(rom, "rom")),
      architecture.getConstructor(classOf[machine.Machine]))
    val romPath = "rom/" + instance.architecture.name
    try {
      val path = new io.File(DimensionManager.getCurrentSaveRootDirectory, Settings.savePath + romPath)
      if ((path.exists || path.mkdirs()) && path.isDirectory && !rom.parts.contains(romPath)) {
        rom.parts += romPath -> FileSystem.fromSaveDirectory(romPath, 0, false)
      }
      else {
        OpenComputers.log.warning(s"Failed mounting user ROM override '$romPath'. It is either not a directory or another mod registered a ROM resource with that name.")
      }
    }
    catch {
      case t: Throwable => OpenComputers.log.log(Level.WARNING, s"Failed mounting user ROM override '$romPath'.", t)
    }
    instance
  }

  /** Possible states of the computer, and in particular its executor. */
  private[component] object State extends Enumeration {
    /** The computer is not running right now and there is no Lua state. */
    val Stopped = Value("Stopped")

    /** Booting up, doing the first run to initialize the kernel and libs. */
    val Starting = Value("Starting")

    /** Computer is currently rebooting. */
    val Restarting = Value("Restarting")

    /** The computer is currently shutting down. */
    val Stopping = Value("Stopping")

    /** The computer is paused and waiting for the game to resume. */
    val Paused = Value("Paused")

    /** The computer executor is waiting for a synchronized call to be made. */
    val SynchronizedCall = Value("SynchronizedCall")

    /** The computer should resume with the result of a synchronized call. */
    val SynchronizedReturn = Value("SynchronizedReturn")

    /** The computer will resume as soon as possible. */
    val Yielded = Value("Yielded")

    /** The computer is yielding for a longer amount of time. */
    val Sleeping = Value("Sleeping")

    /** The computer is up and running, executing Lua code. */
    val Running = Value("Running")
  }

  /** Signals are messages sent to the Lua state from Java asynchronously. */
  private[component] class Signal(val name: String, val args: Array[AnyRef]) extends machine.Signal

  private val threadPool = ThreadPoolFactory.create("Computer", Settings.get.threads)
}