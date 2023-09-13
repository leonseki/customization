package cn.edu.seu.lone.customization.concurrent;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

@Slf4j(topic = "c.SThreadPoolTest")

public class SThreadPoolTest {

    @Test
    public void testCreateSThreadPool() {
        SThreadPool sThreadPool = new SThreadPool(2, 5000, TimeUnit.MILLISECONDS, 10);
        for (int i = 0; i < 5; i++) {
            int j = i;
            sThreadPool.execute(() -> log.debug("执行第{}个任务", j));
        }
    }


}