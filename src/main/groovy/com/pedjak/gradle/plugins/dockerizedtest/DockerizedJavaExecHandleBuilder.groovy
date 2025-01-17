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

import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.file.temp.TemporaryFileProvider
import org.gradle.api.model.ObjectFactory
import org.gradle.initialization.BuildCancellationToken
import org.gradle.internal.file.PathToFileResolver
import org.gradle.process.JavaForkOptions
import org.gradle.process.internal.*
import org.gradle.process.internal.streams.OutputStreamsForwarder

import java.util.concurrent.Executor

class DockerizedJavaExecHandleBuilder extends JavaExecHandleBuilder {

    def streamsHandler
    def executor
    def buildCancellationToken
    private final DockerizedTestExtension extension

    private final WorkerSemaphore workersSemaphore

    DockerizedJavaExecHandleBuilder(DockerizedTestExtension extension, FileResolver fileResolver, Executor executor, BuildCancellationToken buildCancellationToken, WorkerSemaphore workersSemaphore, FileCollectionFactory fileCollectionFactory, ObjectFactory objectFactory, TemporaryFileProvider temporaryFileProvider, PathToFileResolver pathToFileResolver) {
        super(fileResolver,fileCollectionFactory,objectFactory,executor,buildCancellationToken,temporaryFileProvider,null, new DefaultJavaExecSpec(objectFactory,pathToFileResolver,fileCollectionFactory))
//        super(fileResolver, executor, buildCancellationToken)
        this.extension = extension
        this.executor = executor
        this.buildCancellationToken = buildCancellationToken
        this.workersSemaphore = workersSemaphore
    }

    def StreamsHandler getStreamsHandler() {
        StreamsHandler effectiveHandler;
        if (this.streamsHandler != null) {
            effectiveHandler = this.streamsHandler;
        } else {
            boolean shouldReadErrorStream = !redirectErrorStream;
            effectiveHandler = new OutputStreamsForwarder(standardOutput, errorOutput, shouldReadErrorStream);
        }
        return effectiveHandler;
    }

    ExecHandle build() {
        return new ExitCodeTolerantExecHandle(new DockerizedExecHandle(extension, getDisplayName(),
                                                                    getWorkingDir(),
                                                                    'java',
                                                                    allArguments,
                                                                    getActualEnvironment(),
                                                                    getStreamsHandler(),
                                                                    inputHandler,
                                                                    listeners,
                                                                    redirectErrorStream,
                                                                    timeoutMillis,
                                                                    daemon,
                                                                    executor,
                                                                    buildCancellationToken),
                workersSemaphore)

    }

    def timeoutMillis = Integer.MAX_VALUE
    @Override
    AbstractExecHandleBuilder setTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis
        return super.setTimeout(timeoutMillis)
    }

    boolean redirectErrorStream
    @Override
    AbstractExecHandleBuilder redirectErrorStream() {
        redirectErrorStream = true
        return super.redirectErrorStream()
    }

    def listeners = []
    @Override
    AbstractExecHandleBuilder listener(ExecHandleListener listener) {
        listeners << listener
        return super.listener(listener)
    }

}
