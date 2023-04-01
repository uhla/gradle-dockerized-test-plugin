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

package com.pedjak.gradle.plugins.dockerizedtest;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.gradle.process.ExecResult;
import org.gradle.process.internal.ExecException;
import org.gradle.process.internal.ExecHandle;
import org.gradle.process.internal.ExecHandleListener;
import org.gradle.process.internal.ExecHandleState;

/**
 * All exit codes are normal
 */
public class ExitCodeTolerantExecHandle implements ExecHandle {

    private final WorkerSemaphore testWorkerSemaphore;

    //    @Delegate
    private final ExecHandle delegate;

    private boolean needsReleasing = false;

    ExitCodeTolerantExecHandle(ExecHandle delegate, WorkerSemaphore testWorkerSemaphore) {
        this.delegate = delegate;
        this.testWorkerSemaphore = testWorkerSemaphore;
        delegate.addListener(new ExecHandleListener() {

            @Override
            public void beforeExecutionStarted(final ExecHandle execHandle) {
                // do nothing
            }

            @Override
            public void executionStarted(ExecHandle execHandle) {
                // do nothing
            }

            @Override
            public void executionFinished(ExecHandle execHandle, ExecResult execResult) {
                synchronized (delegate) {
                    if (needsReleasing) {
                        testWorkerSemaphore.release();
                        needsReleasing = false;
                    }
                }
            }
        });
    }

    @Override
    public File getDirectory() {
        return delegate.getDirectory();
    }

    @Override
    public String getCommand() {
        return delegate.getCommand();
    }

    @Override
    public List<String> getArguments() {
        return delegate.getArguments();
    }

    @Override
    public Map<String, String> getEnvironment() {
        return delegate.getEnvironment();
    }

    @Override
    public ExecHandle start() {
        synchronized (delegate) {
            testWorkerSemaphore.acquire();
            needsReleasing = true;
        }
        try {
            delegate.start();
        } catch (Exception e) {
            synchronized (delegate) {
                if (needsReleasing) {
                    testWorkerSemaphore.release();
                    needsReleasing = false;
                }
            }
            throw e;
        }
        return this;
    }

    @Override
    public ExecHandleState getState() {
        return delegate.getState();
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public ExecResult waitForFinish() {
        return delegate.waitForFinish();
    }

    @Override
    public void addListener(final ExecHandleListener execHandleListener) {
        delegate.addListener(execHandleListener);
    }

    @Override
    public void removeListener(final ExecHandleListener execHandleListener) {
        delegate.removeListener(execHandleListener);
    }

    private static class ExitCodeTolerantExecResult implements ExecResult {

        private final ExecResult delegate;

        ExitCodeTolerantExecResult(ExecResult delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getExitValue() {
            return delegate.getExitValue();
        }

        @Override
        public ExecResult assertNormalExitValue() throws ExecException {
            // no op because we are perfectly ok if the exit code is anything
            // because Docker can complain about not being able to remove the used image
            // although the tests completed fine
            return this;
        }

        @Override
        public ExecResult rethrowFailure() throws ExecException {
            return delegate.rethrowFailure();
        }
    }

    private static class ExecHandleListenerFacade implements ExecHandleListener {

        private final ExecHandleListener delegate;

        ExecHandleListenerFacade(ExecHandleListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void beforeExecutionStarted(final ExecHandle execHandle) {
            delegate.beforeExecutionStarted(execHandle);
        }

        @Override
        public void executionStarted(final ExecHandle execHandle) {
            delegate.executionStarted(execHandle);
        }

        @Override
        public void executionFinished(ExecHandle execHandle, ExecResult execResult) {
            delegate.executionFinished(execHandle, new ExitCodeTolerantExecResult(execResult));
        }
    }
}
