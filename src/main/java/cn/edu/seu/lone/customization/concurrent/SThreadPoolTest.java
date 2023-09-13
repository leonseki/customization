package cn.edu.seu.lone.customization.concurrent;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * @author lone
 */
@Slf4j(topic = "c.SThreadPoolTest")
public class SThreadPoolTest {
    public static void main(String[] args) {
        SThreadPool sThreadPool = new SThreadPool(1, 1000, TimeUnit.MILLISECONDS, 1, ((queue, task) -> {
            // queue.put(task);
            // queue.offer(task);
            // log.info("放弃{}", task);
            // throw new RuntimeException();
            task.run();
        }));
        for (int i = 0; i < 3; i++) {
            int j = i;
            sThreadPool.execute(() -> {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                log.debug("执行第{}个任务", j);
            });
        }
    }
}
