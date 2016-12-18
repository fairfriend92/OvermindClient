package com.example.overmind;

/**
 * Created by root on 17/12/16.
 */

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThreadPool {

    // A single instance of ThreadPool, used to implement the singleton pattern
    private static ThreadPool sInstance = null;

    // A managed pool of background kernel initialization threads
    private final ThreadPoolExecutor mKernelInitThreadPool;

    // A queue of Runnables for the kernel initialization pool
    private final BlockingQueue<Runnable> mKernelInitWorkQueue;

    // Sets the Time Unit to seconds
    private static final TimeUnit KEEP_ALIVE_TIME_UNIT;

    // Sets the amount of time an idle thread will wait for a task before terminating
    private static final int KEEP_ALIVE_TIME = 2;

    // Sets the initial threadpool size to 8
    private static final int CORE_POOL_SIZE = 8;

    // Sets the maximum threadpool size to 8
    private static final int MAXIMUM_POOL_SIZE = 8;

    static {
        // The time unit for "keep alive" is in milliseconds
        KEEP_ALIVE_TIME_UNIT = TimeUnit.MILLISECONDS;

        // Create a single instance of ThreadPool
        sInstance = new ThreadPool();
    }

    /**
     * Construct the queues and thread pools used to initialize the kernel, simulate the network and upload
     * the post-synaptic spikes. The constructor is marked private so it's unavailable to other classes
     */
    private ThreadPool() {

        /*
         * Creates a work queue for the pool of Thread objects used for kernel initialization, using a linked
         * list queue that blocks when the queue is empty.
         */
        mKernelInitWorkQueue = new LinkedBlockingQueue<Runnable>();

        /**
         * Creates a new pool of Thread objects for the kernel initialization work queue
         */
        mKernelInitThreadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mKernelInitWorkQueue);
    }

}




