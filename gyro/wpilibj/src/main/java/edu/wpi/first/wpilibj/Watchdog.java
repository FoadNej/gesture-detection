/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

package edu.wpi.first.wpilibj;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A class that's a wrapper around a watchdog timer.
 *
 * <p>When the timer expires, a message is printed to the console and an optional user-provided
 * callback is invoked.
 *
 * <p>The watchdog is initialized disabled, so the user needs to call enable() before use.
 */
public class Watchdog implements Closeable, Comparable<Watchdog> {
  private long m_startTime; // us
  private long m_timeout; // us
  private long m_expirationTime; // us
  private final Runnable m_callback;

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final Map<String, Long> m_epochs = new HashMap<>();
  boolean m_isExpired;

  static {
    startDaemonThread(() -> schedulerFunc());
  }

  private static final PriorityQueue<Watchdog> m_watchdogs = new PriorityQueue<>();
  private static ReentrantLock m_queueMutex = new ReentrantLock();
  private static Condition m_schedulerWaiter = m_queueMutex.newCondition();

  /**
   * Watchdog constructor.
   *
   * @param timeout  The watchdog's timeout in seconds with microsecond resolution.
   * @param callback This function is called when the timeout expires.
   */
  public Watchdog(double timeout, Runnable callback) {
    m_timeout = (long) (timeout * 1.0e6);
    m_callback = callback;
  }

  @Override
  public void close() {
    disable();
  }

  @Override
  public int compareTo(Watchdog rhs) {
    // Elements with sooner expiration times are sorted as lesser. The head of
    // Java's PriorityQueue is the least element.
    if (m_expirationTime < rhs.m_expirationTime) {
      return -1;
    } else if (m_expirationTime > rhs.m_expirationTime) {
      return 1;
    } else {
      return 0;
    }
  }

  /**
   * Returns the time in seconds since the watchdog was last fed.
   */
  public double getTime() {
    return (RobotController.getFPGATime() - m_startTime) / 1.0e6;
  }

  /**
   * Sets the watchdog's timeout.
   *
   * @param timeout The watchdog's timeout in seconds with microsecond
   *                resolution.
   */
  public void setTimeout(double timeout) {
    m_startTime = RobotController.getFPGATime();
    m_epochs.clear();

    m_queueMutex.lock();
    try {
      m_timeout = (long) (timeout * 1.0e6);
      m_isExpired = false;

      m_watchdogs.remove(this);
      m_expirationTime = m_startTime + m_timeout;
      m_watchdogs.add(this);
      m_schedulerWaiter.signalAll();
    } finally {
      m_queueMutex.unlock();
    }
  }

  /**
   * Returns the watchdog's timeout in seconds.
   */
  public double getTimeout() {
    m_queueMutex.lock();
    try {
      return m_timeout / 1.0e6;
    } finally {
      m_queueMutex.unlock();
    }
  }

  /**
   * Returns true if the watchdog timer has expired.
   */
  public boolean isExpired() {
    m_queueMutex.lock();
    try {
      return m_isExpired;
    } finally {
      m_queueMutex.unlock();
    }
  }

  /**
   * Adds time since last epoch to the list printed by printEpochs().
   *
   * <p>Epochs are a way to partition the time elapsed so that when overruns occur, one can
   * determine which parts of an operation consumed the most time.
   *
   * @param epochName The name to associate with the epoch.
   */
  public void addEpoch(String epochName) {
    long currentTime = RobotController.getFPGATime();
    m_epochs.put(epochName, currentTime - m_startTime);
    m_startTime = currentTime;
  }

  /**
   * Prints list of epochs added so far and their times.
   */
  public void printEpochs() {
    m_epochs.forEach((key, value) -> {
      System.out.format("\t" + key + ": %.6fs\n", value / 1.0e6);
    });
  }

  /**
   * Resets the watchdog timer.
   *
   * <p>This also enables the timer if it was previously disabled.
   */
  public void reset() {
    enable();
  }

  /**
   * Enables the watchdog timer.
   */
  public void enable() {
    m_startTime = RobotController.getFPGATime();
    m_epochs.clear();

    m_queueMutex.lock();
    try {
      m_isExpired = false;

      m_watchdogs.remove(this);
      m_expirationTime = m_startTime + m_timeout;
      m_watchdogs.add(this);
      m_schedulerWaiter.signalAll();
    } finally {
      m_queueMutex.unlock();
    }
  }

  /**
   * Disables the watchdog timer.
   */
  public void disable() {
    m_queueMutex.lock();
    try {
      m_isExpired = false;

      m_watchdogs.remove(this);
      m_schedulerWaiter.signalAll();
    } finally {
      m_queueMutex.unlock();
    }
  }

  private static Thread startDaemonThread(Runnable target) {
    Thread inst = new Thread(target);
    inst.setDaemon(true);
    inst.start();
    return inst;
  }


  private static void schedulerFunc() {
    m_queueMutex.lock();

    try {
      while (true) {
        if (m_watchdogs.size() > 0) {
          boolean timedOut = !awaitUntil(m_schedulerWaiter, m_watchdogs.peek().m_expirationTime);
          if (timedOut) {
            if (m_watchdogs.size() == 0 || m_watchdogs.peek().m_expirationTime
                > RobotController.getFPGATime()) {
              continue;
            }

            // If the condition variable timed out, that means a Watchdog timeout
            // has occurred, so call its timeout function.
            Watchdog watchdog = m_watchdogs.poll();

            System.out.format("Watchdog not fed within %.6fs\n", watchdog.m_timeout / 1.0e6);
            m_queueMutex.unlock();
            watchdog.m_callback.run();
            m_queueMutex.lock();
            watchdog.m_isExpired = true;
          }
          // Otherwise, a Watchdog removed itself from the queue (it notifies
          // the scheduler of this) or a spurious wakeup occurred, so just
          // rewait with the soonest watchdog timeout.
        } else {
          while (m_watchdogs.size() == 0) {
            m_schedulerWaiter.awaitUninterruptibly();
          }
        }
      }
    } finally {
      m_queueMutex.unlock();
    }
  }

  /**
   * Wrapper emulating functionality of C++'s std::condition_variable::wait_until().
   *
   * @param cond The condition variable on which to wait.
   * @param time The time at which to stop waiting.
   * @return False if the deadline has elapsed upon return, else true.
   */
  private static boolean awaitUntil(Condition cond, long time) {
    long delta = time - RobotController.getFPGATime();
    try {
      if (delta > 0) {
        return cond.await(delta, TimeUnit.MICROSECONDS);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      ex.printStackTrace();
    }

    return true;
  }
}
