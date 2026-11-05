package com.anitec.backend;
import com.anitec.backend.identity.infrastructure.BoundedPasswordRecoveryQueue;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class PasswordRecoveryQueueTests {
 @Test void boundedQueueRejectsOverflowAndShutdown()throws Exception{var queue=new BoundedPasswordRecoveryQueue();var running=new CountDownLatch(2);var release=new CountDownLatch(1);Runnable block=()->{running.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}};try{queue.submit(block);queue.submit(block);assertThat(running.await(5,TimeUnit.SECONDS)).isTrue();for(int i=0;i<256;i++)queue.submit(()->{});assertThatThrownBy(()->queue.submit(()->{})).isInstanceOf(RejectedExecutionException.class);}finally{release.countDown();queue.close();}assertThatThrownBy(()->queue.submit(()->{})).isInstanceOf(RejectedExecutionException.class);}
}
