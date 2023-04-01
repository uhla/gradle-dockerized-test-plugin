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

import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.process.internal.*;
import org.gradle.process.internal.streams.OutputStreamsForwarder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class DockerizedJavaExecHandleBuilder extends JavaExecHandleBuilder {

    private StreamsHandler streamsHandler;
    private final Executor executor;
    private final BuildCancellationToken buildCancellationToken;
    private final DockerizedTestExtension extension;

    private final WorkerSemaphore workersSemaphore;

    DockerizedJavaExecHandleBuilder(DockerizedTestExtension extension, FileResolver fileResolver, Executor executor,
                                    BuildCancellationToken buildCancellationToken, WorkerSemaphore workersSemaphore,
                                    FileCollectionFactory fileCollectionFactory, ObjectFactory objectFactory,
                                    TemporaryFileProvider temporaryFileProvider, PathToFileResolver pathToFileResolver) {
        super(fileResolver, fileCollectionFactory, objectFactory, executor, buildCancellationToken, temporaryFileProvider, null,
                new DefaultJavaExecSpec(objectFactory, pathToFileResolver, fileCollectionFactory));
        //        super(fileResolver, executor, buildCancellationToken)
        this.extension = extension;
        this.executor = executor;
        this.buildCancellationToken = buildCancellationToken;
        this.workersSemaphore = workersSemaphore;
    }

    private StreamsHandler getStreamsHandler() {
        StreamsHandler effectiveHandler;
        if (this.streamsHandler != null) {
            effectiveHandler = this.streamsHandler;
        } else {
            boolean shouldReadErrorStream = !redirectErrorStream;
            effectiveHandler = new OutputStreamsForwarder(getStandardOutput(), getErrorOutput(), shouldReadErrorStream);
        }
        return effectiveHandler;
    }

    @Override
    public ExecHandle build() {

        return new ExitCodeTolerantExecHandle(new DockerizedExecHandle(extension, getDisplayName(),
                getWorkingDir(),
                "java",
                getAllArguments(),
                getActualEnvironment(),
                getStreamsHandler(),
                getInputHandler(),
                listeners,
                redirectErrorStream,
                timeoutMillis,
                daemon,
                executor,
                buildCancellationToken),
                workersSemaphore);

    }

    private int timeoutMillis = Integer.MAX_VALUE;

    @Override
    public AbstractExecHandleBuilder setTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return super.setTimeout(timeoutMillis);
    }

    boolean redirectErrorStream;

    @Override
    public AbstractExecHandleBuilder redirectErrorStream() {
        redirectErrorStream = true;
        return super.redirectErrorStream();
    }

    private final List<ExecHandleListener> listeners = new ArrayList<>();

    @Override
    public AbstractExecHandleBuilder listener(ExecHandleListener listener) {
        listeners.add(listener);

        return super.listener(listener);
    }

}
