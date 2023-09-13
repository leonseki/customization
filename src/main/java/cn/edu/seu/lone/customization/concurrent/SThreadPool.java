package cn.edu.seu.lone.customization.concurrent;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@FunctionalInterface
interface RejectPolicy<T> {
    void reject(BlockingQueue<T> queue, T task);
}

/**
 * @author lone
 */
@Slf4j(topic = "c.SThreadPool")
public class SThreadPool {
    // 阻塞队列
    private final BlockingQueue<Runnable> taskQueue;

    // 线程集合
    private final HashSet<Worker> workers = new HashSet<>();

    // 核心线程数
    private final int coreSize;

    // 超时时间
    private final long timeout;

    // 超时时间单位
    private final TimeUnit timeUnit;

    private RejectPolicy<Runnable> rejectPolicy;

    public SThreadPool(int coreSize, long timeout, TimeUnit timeUnit, int queueCapacity, RejectPolicy<Runnable> rejectPolicy) {
        this.coreSize = coreSize;
        this.timeout = timeout;
        this.timeUnit = timeUnit;
        this.taskQueue = new BlockingQueue<>(queueCapacity);
        this.rejectPolicy = rejectPolicy;
    }

    public void execute(Runnable task) {
        // 当任务数没有超过 coreSize 时，直接交给worker对象执行
        // 如果任务数超过 coreSize 时， 加入任务队列
        synchronized (workers) {
            if (workers.size() < coreSize) {
                Worker worker = new Worker(task);
                log.debug("新增 worker{}, {}", worker, task);
                workers.add(worker);
                worker.start();
            } else {
                // taskQueue.put(task);
                taskQueue.tryPut(rejectPolicy, task);
            }
        }

    }
    class Worker extends Thread{
        private Runnable task;

        public Worker(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            // 执行任务
            // 1）当task 不为空，执行任务
            // 2）当 task 执行完毕，从任务队列中获取任务并执行
            // while (Objects.nonNull(task) || Objects.nonNull(task = taskQueue.take())) {
            // 带有超时时间的获取
            while (task != null || (task = taskQueue.poll(timeout, timeUnit)) != null ) {
                try {
                    log.debug("正在执行...{}", task);
                    task.run();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    task = null;
                }
            }
            synchronized (workers) {
                log.debug("worker 被移除{}", task);
                workers.remove(this);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Worker worker = (Worker) o;
            return Objects.equals(task, worker.task);
        }

        @Override
        public int hashCode() {
            return Objects.hash(task);
        }
    }

}

@Slf4j(topic = "c.BlockingQueue")
class BlockingQueue<T> {
    // 1. 任务队列
    private final Deque<T> queue = new ArrayDeque<>();

    // 2.多线程操作队列，需要加锁
    private final ReentrantLock lock = new ReentrantLock();

    // 3.消费者条件变量-队列为空时，消费者需要等
    private final Condition emptyWaitSet = lock.newCondition();

    // 4.生产者条件变量-队列满时，生产者需要等
    private final Condition fullWaitSet = lock.newCondition();

    // 5.容量
    private final int capacity;

    public BlockingQueue(int capacity) {
        this.capacity = capacity;
    }

    // 阻塞获取
    public T take() {
        lock.lock();
        try {
            // 没有元素则等待
            while (queue.isEmpty()) {
                try {
                    emptyWaitSet.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            T task =  queue.removeFirst();
            // 移除元素后，需要唤醒生产者的等待队列
            fullWaitSet.signal();
            return task;
        } finally {
            lock.unlock();
        }
    }

    // 带有超时时间的阻塞获取
    public T poll(long timeout, TimeUnit timeUnit) {
        lock.lock();
        try {
            long nanos = timeUnit.toNanos(timeout);
            while (queue.isEmpty()) {
                try {
                    if (nanos <= 0) {
                        return null;
                    }
                    nanos = emptyWaitSet.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            T task =  queue.removeFirst();
            // 移除元素后，需要唤醒生产者的等待队列
            fullWaitSet.signal();
            return task;
        } finally {
            lock.unlock();
        }
    }

    // 阻塞添加
    public void put(T task) {
        lock.lock();
        try {
            // 判断队列是否满了
            while (queue.size() == capacity) {
                try {
                    log.info("等待加入任务队列{}", task);
                    fullWaitSet.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            log.debug("加入任务队列，{}", task);
            queue.addLast(task);
            // 队列添加元素后需要唤醒消费者的等待队列
            emptyWaitSet.signal();
        } finally {
            lock.unlock();
        }
    }

    // 带超时时间的阻塞添加
    public boolean offer(T task, long timeout, TimeUnit timeUnit) {
        lock.lock();
        try {
            long nanos = timeUnit.toNanos(timeout);
            // 判断队列是否满了
            while (queue.size() == capacity) {
                try {
                    log.info("等待加入任务队列{}", task);
                    if (nanos <= 0) {
                        return false;
                    }
                    nanos = fullWaitSet.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            log.debug("加入任务队列，{}", task);
            queue.addLast(task);
            // 队列添加元素后需要唤醒消费者的等待队列
            emptyWaitSet.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    // 获取大小
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public void tryPut(RejectPolicy<T> rejectPolicy, T task) {
        lock.lock();
        try {
            // 判断队列是否已满
            if (queue.size() == capacity) {
                rejectPolicy.reject(this, task);
            } else {
                log.debug("加入任务队列，{}", task);
                queue.addLast(task);
                // 队列添加元素后需要唤醒消费者的等待队列
                emptyWaitSet.signal();
            }
        } finally {
            lock.unlock();
        }
    }
}
