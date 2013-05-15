package com.sanketkalwit.utilities;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Helps manage timed tasks meant to occur after a certain duration. Uses one
 * {@link java.util.Timer} per instance.
 * 
 * @author Sanket Kalwit (ithrewarock@gmail.com)
 * 
 */
public class TimedTaskManager {

	private final int maxVariables;
	private final int updateInterval;
	private Timer updateTimer;

	private final TimedTask[] taskArray;
	private volatile int curSize = 0;

	private TimedTaskManager that = this;

	static Thread lazy;
	static LazyManagedTask head;
	static LazyManagedTask tail;
	static volatile boolean lazyEnabled = false;

	/**
	 * Spawns a thread to allow lazy tasks. Required for lazy tasks to work.
	 */
	public static void enableLazyTasks() {
		lazy = new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					synchronized (lazy) {
						if (Thread.currentThread() != lazy) {
							break;
						}
						if (head != null) {
							head.execTask();
							head = head.next;
						} else if (!lazyEnabled) {
							break;
						}
					}
					Thread.yield();
				}
				lazy = null;
			}

		});
		lazyEnabled = true;
		lazy.start();
	}

	/**
	 * Disabled lazy tasks after completing queued tasks. A task is considered
	 * "queued" when the timer on it runs out. Example If a task is timed to run
	 * after 100 ms, it is queued once 100ms have passed.
	 */
	public static void disableLazy() {
		lazyEnabled = false;
	}

	/**
	 * Disables lazy tasks and cancels all queues tasks. Shuts down currently
	 * running tasks. May throw an exception.
	 */
	@Deprecated
	public static void shutdownLazy() {
		if (!lazyEnabled) {
			return;
		}
		disableLazy();
		if(lazy != null) {
			lazy.stop();
		}
	}

	public TimedTaskManager(int maxSize, int period) {
		this.maxVariables = maxSize;
		this.updateInterval = period;

		taskArray = new TimedTask[maxVariables];
		for (int i = 0; i < taskArray.length; i++) {
			taskArray[i] = new TimedTask();
		}
	}

	/**
	 * Default settings: interval of 100ms.
	 * 
	 * @param size
	 */
	public TimedTaskManager(int size) {
		this(size, 100);
	}

	/**
	 * Default settings: manages 100 tasks at a time, with an interval of 100ms.
	 */
	public TimedTaskManager() {
		this(100);
	}

	/**
	 * Shuts down the timer. Does not disable lazy tasks.
	 */
	public synchronized void stop() {
		if (updateTimer != null) {
			this.updateTimer.cancel();
			this.updateTimer = null;
		}
	}

	/**
	 * Start the timer.
	 * 
	 * @throws IllegalStateException
	 *           if called on an already running manager.
	 */
	public synchronized void start() {
		if (updateTimer != null) {
			throw new IllegalStateException();
		}
		updateTimer = new Timer();
		updateTimer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				for (TimedTask setter : taskArray) {
					setter.decrement();
				}
			}

		}, 0, 100);
	}

	/**
	 * Add a task to be run after a specified amount of time. Once the task has
	 * been run, the task will be removed from the list to make room for future
	 * tasks. Task is run after specified duration by calling execute();
	 * 
	 * @param time
	 *          duration after which task should be executed.
	 */
	public void add(int time, ManagedTask task) {
		if (time <= 0) {
			throw new IllegalArgumentException("Time must be greater than 0.");
		}
		synchronized (that) {
			if (curSize == taskArray.length) {
				throw new IndexOutOfBoundsException("Can only manage " + maxVariables
				    + "variables at any given time.");
			} else {
				curSize++;
			}
		}
		TimedTask temp;
		time = (time > updateInterval) ? time / updateInterval : 1;
		for (int i = 0; i < taskArray.length; i++) {
			temp = taskArray[i];
			if (temp.set(time, task)) {
				break;
			}
		}
	}

	private class TimedTask {
		volatile int counter = -1; // -1 = unused, -2 = about to be used, >0
		// counting down
		private ManagedTask setter = null;

		private TimedTask() {
		}

		/**
		 * Attempts to set a new task with a new counter. Returns true if task was
		 * successfully set. Returns false if this TimedTask is already being used.
		 */
		boolean set(int counter, ManagedTask setter) {
			if (counter <= 0) {
				throw new IllegalArgumentException("Counter must be greater than 0.");
			}
			synchronized (this) {
				if (this.counter != -1) {
					return false;
				}
				this.counter = -2;
			}
			this.setter = setter;
			this.counter = counter;
			return true;
		}

		/**
		 * Decrements the counter on this if it's greater than 0. Executes event if
		 * counter reaches 0. Does nothing if counter is greater than 0.
		 */
		void decrement() {
			if (counter <= 0) {
				return; // do nothing
			}
			counter--;
			if (counter == 0) {
				setter.execute();
				counter = -1;
				synchronized (that) {
					curSize--;
				}
				setter = null;
			}
		}
	}

	/**
	 * Interface to define a task to be managed by TimedTaskManager.
	 * 
	 * @author Sanket Kalwit (ithrewarock@gmail.com)
	 */
	public static abstract class ManagedTask {

		abstract void execute();

		/**
		 * Slow/Long tasks may cause performance issues. Calling async returns a
		 * task that run in its own thread. Use sparingly, as each execution will
		 * spawn its own thread. Good for tasks that need to be executed immedietly.
		 */
		public final ManagedTask async() {
			return new AsyncManagedTask(this);
		}

		/**
		 * Slow/Long tasks may cause performance issues. Calling lazy returns a task
		 * that will run when all other lazy tasks are completed. Good for tasks
		 * that can wait.
		 * 
		 * @return
		 */
		public final ManagedTask lazy() {
			if (!lazyEnabled) {
				throw new IllegalStateException(
				    "Cannot create lazy task without enable lazy tasks.");
			}
			return new LazyManagedTask(this);
		}
	}

	private static class LazyManagedTask extends ManagedTask {

		ManagedTask task;
		LazyManagedTask next;

		private LazyManagedTask(ManagedTask task) {
			this.task = task;
		}

		public void execTask() {
			task.execute();
		}

		@Override
		void execute() {
			if(!lazyEnabled) {
				return;
			}
			synchronized (lazy) {
				if (head == null) {
					head = tail = this;
				} else {
					tail.next = this;
					tail = this;
				}
			}
		}

	}

	private static class AsyncManagedTask extends ManagedTask implements Runnable {

		ManagedTask task;

		private AsyncManagedTask(ManagedTask task) {
			this.task = task;
		}

		public void run() {
			task.execute();
		}

		@Override
		void execute() {
			new Thread(this).start();
		}

	}
}