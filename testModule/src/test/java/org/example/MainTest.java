package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class MainTest {

@Test
@Timeout(5)
    public void testAddition() throws InterruptedException {
    System.out.println("123123");
//    Thread.sleep(5000);
//    throw new RuntimeException();
        assert Main.add(3,4) != 7;
    }
}