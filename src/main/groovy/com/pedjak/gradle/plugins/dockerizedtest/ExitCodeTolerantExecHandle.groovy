/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedjak.gradle.plugins.dockerizedtest

import org.gradle.process.ExecResult
import org.gradle.process.internal.ExecException
import org.gradle.process.internal.ExecHandle
import org.gradle.process.internal.ExecHandleListener

/**
 * All exit codes are normal
 */
class ExitCodeTolerantExecHandle implements ExecHandle {

    private final WorkerSemaphore testWorkerSemaphore

    @Delegate
    private final ExecHandle delegate

    private boolean needsReleasing = false

    ExitCodeTolerantExecHandle(ExecHandle delegate, WorkerSemaphore testWorkerSemaphore) {
        this.delegate = delegate
        this.testWorkerSemaphore = testWorkerSemaphore
        delegate.addListener(new ExecHandleListener() {

            @Override
            void beforeExecutionStarted(final ExecHandle execHandle) {
            }

            @Override
            void executionStarted(ExecHandle execHandle) {
                // do nothing
            }

            @Override
            void executionFinished(ExecHandle execHandle, ExecResult execResult) {
                synchronized (delegate) {
                    if (needsReleasing) {
                        testWorkerSemaphore.release()
                        needsReleasing = false
                    }
                }
            }
        })
    }

    ExecHandle start() {
        synchronized(delegate) {
            testWorkerSemaphore.acquire()
            needsReleasing = true
        }
        try {
            delegate.start()
        } catch (Exception e) {
            synchronized (delegate) {
                if (needsReleasing) {
                    testWorkerSemaphore.release()
                    needsReleasing = false
                }
            }
            throw e
        }
    }

    private static class ExitCodeTolerantExecResult implements ExecResult {

        @Delegate
        private final ExecResult delegate

        ExitCodeTolerantExecResult(ExecResult delegate) {
            this.delegate = delegate
        }

        ExecResult assertNormalExitValue() throws ExecException {
            // no op because we are perfectly ok if the exit code is anything
            // because Docker can complain about not being able to remove the used image
            // although the tests completed fine
            this
        }
    }

    private static class ExecHandleListenerFacade implements ExecHandleListener {

        @Delegate
        private final ExecHandleListener delegate

        ExecHandleListenerFacade(ExecHandleListener delegate) {
            this.delegate = delegate
        }

        void executionFinished(ExecHandle execHandle, ExecResult execResult) {
            delegate.executionFinished(execHandle, new ExitCodeTolerantExecResult(execResult))
        }
    }
}
