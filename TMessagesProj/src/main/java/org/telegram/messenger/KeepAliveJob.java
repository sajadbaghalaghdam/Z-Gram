/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.Intent;

import org.telegram.messenger.support.JobIntentService;

import java.util.concurrent.CountDownLatch;

public class KeepAliveJob extends JobIntentService {

    private static volatile CountDownLatch countDownLatch;
    private static volatile boolean startingJob;
    private static final Object sync = new Object();

    /**
     * ZG battery (F-04): upper bound for how long the job (and the wakelock behind it) waits
     * for the TLRPC.Updates that follow an internal push. The latch is normally released within
     * a few seconds by ConnectionsManager; this ceiling only matters when getDifference is slow,
     * which is exactly when the notification still needs the CPU, so it is deliberately generous.
     */
    private static final long KEEP_ALIVE_TIMEOUT_MS = 60 * 1000;

    public static void startJob() {
        Utilities.globalQueue.postRunnable(() -> {
            if (startingJob || countDownLatch != null) {
                return;
            }
            try {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("starting keep-alive job");
                }
                synchronized (sync) {
                    startingJob = true;
                }
                enqueueWork(ApplicationLoader.applicationContext, KeepAliveJob.class, 1000, new Intent());
            } catch (Exception ignore) {

            }
        });
    }

    private static void finishJobInternal() {
        synchronized (sync) {
            if (countDownLatch != null) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("finish keep-alive job");
                }
                countDownLatch.countDown();
            }
            if (startingJob) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("finish queued keep-alive job");
                }
                startingJob = false;
            }
        }
    }

    public static void finishJob() {
        Utilities.globalQueue.postRunnable(KeepAliveJob::finishJobInternal);
    }

    private static Runnable finishJobByTimeoutRunnable = KeepAliveJob::finishJobInternal;

    @Override
    protected void onHandleWork(Intent intent) {
        synchronized (sync) {
            if (!startingJob) {
                return;
            }
            if (!ApplicationLoader.mainInterfacePaused) {
                // ZG battery (F-04): the UI is in the foreground, so the update is processed and
                // shown without any help from a wakelock - do not block a worker for it.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("skip keep-alive job, app is in foreground");
                }
                startingJob = false;
                return;
            }
            countDownLatch = new CountDownLatch(1);
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("started keep-alive job");
        }
        Utilities.globalQueue.postRunnable(finishJobByTimeoutRunnable, KEEP_ALIVE_TIMEOUT_MS);
        try {
            countDownLatch.await();
        } catch (Throwable ignore) {

        }
        Utilities.globalQueue.cancelRunnable(finishJobByTimeoutRunnable);
        synchronized (sync) {
            countDownLatch = null;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("ended keep-alive job");
        }
    }
}
