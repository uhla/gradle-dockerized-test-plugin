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
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import org.gradle.StartParameter;
import org.gradle.api.*;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.logging.LoggingManager;
import org.gradle.api.model.ObjectFactory;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.concurrent.DefaultExecutorFactory;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.operations.BuildOperationExecutor;;
import org.gradle.internal.remote.Address;
import org.gradle.internal.remote.ConnectionAcceptor;
import org.gradle.internal.remote.MessagingServer;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.internal.remote.internal.ConnectCompletion;
import org.gradle.internal.remote.internal.IncomingConnector;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.gradle.internal.remote.internal.hub.MessageHubBackedObjectConnection;
import org.gradle.internal.remote.internal.inet.MultiChoiceAddress;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.time.Clock;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.process.internal.JavaExecHandleFactory;
import org.gradle.process.internal.health.memory.MemoryManager;
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory;
import org.gradle.process.internal.worker.WorkerProcessFactory;

import javax.inject.Inject;

import static com.pedjak.gradle.plugins.dockerizedtest.GroovyHelpers.getCurrentUserId;

class DockerizedTestPlugin implements Plugin<Project> {

    private final String supportedVersion = "7.6";
    private final String currentUser;
    private final MessagingServer messagingServer;
    private static final WorkerSemaphore workerSemaphore = new DefaultWorkerSemaphore();
    private final MemoryManager memoryManager = new NoMemoryManager();
    private final ServiceRegistry serviceRegistry;
    private final ModuleRegistry moduleRegistry;
    private final ActorFactory actorFactory;
    private final FileResolver fileResolver;
    private final LoggingManager loggingManager;

    @Inject
    DockerizedTestPlugin(MessagingServer messagingServer, ServiceRegistry serviceRegistry, ModuleRegistry moduleRegistry, ActorFactory actorFactory,
                         FileResolver fileResolver, LoggingManager loggingManager) {
        this.currentUser = SystemUtils.IS_OS_WINDOWS ? "0" : getCurrentUserId.toString();
        this.messagingServer = messagingServer;// new MessageServer(messagingServer.connector, messagingServer.executorFactory); ? FIXME
        this.serviceRegistry = serviceRegistry;
        this.moduleRegistry = moduleRegistry;
        this.actorFactory = actorFactory;
        this.fileResolver = fileResolver;
        this.loggingManager = loggingManager;
    }

    void configureTest(Project project, Task test) {
        DockerizedTestExtension ext = test.getExtensions().create("docker", DockerizedTestExtension.class);
        StartParameter startParameter = project.getGradle().getStartParameter();

        Map<String, String> volumesMap = new HashMap<>();
        volumesMap.put(startParameter.getGradleUserHomeDir().toString(), startParameter.getGradleUserHomeDir().toString());
        volumesMap.put(project.getProjectDir().toString(), project.getProjectDir().toString());
        //
        //        throw new RuntimeException()
        ext.setVolumes(volumesMap);
        ext.setUser(currentUser);

        test.doFirst(first -> {
            final DockerizedTestExtension docker = (DockerizedTestExtension) test.getExtensions().getByName("docker");
            if (docker.getImage() != null) {
                workerSemaphore.applyTo(test.getProject());
                test.setProperty("testExecuter", new TestExecuter(newProcessBuilderFactory(docker, //test.processBuilderFactory,
                        startParameter.getGradleUserHomeDir(), serviceRegistry), actorFactory, moduleRegistry,
                        serviceRegistry.get(BuildOperationExecutor.class),
                        serviceRegistry.get(Clock.class), serviceRegistry.get(WorkerLeaseService.class)));

                if (docker.getClient() == null) {
                    docker.setClient(createDefaultClient());
                }
            }
        });

    }

    static DockerClient createDefaultClient() {
        return DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder().build())
                // TODO?
                //                    .withDockerCmdExecFactory(new DockerHttpClient()new NettyDockerCmdExecFactory())
                .build();
    }

    @Override
    public void apply(Project project) {

        boolean unsupportedVersion = new ComparableVersion(project.getGradle().getGradleVersion()).compareTo(new ComparableVersion(supportedVersion)) < 0;
        if (unsupportedVersion) {
            throw new GradleException("dockerized-test plugin requires Gradle ${supportedVersion}+");
        }

        project.getTasks().withType(Test.class).forEach(test -> configureTest(project, test));
        project.getTasks().whenTaskAdded((Action<? super Task>) task -> {
                    if (task instanceof Test) {
                        configureTest(project, task);
                    }
                }
        );
    }

    private WorkerProcessFactory newProcessBuilderFactory(DockerizedTestExtension extension, File gradleUserHome, ServiceRegistry services) {

        ExecutorFactory executorFactory = new DefaultExecutorFactory();
        Executor executor = executorFactory.create("Docker container link");
        BuildCancellationToken buildCancellationToken = new DefaultBuildCancellationToken();
        JavaExecHandleFactory javaExecHandleFactory = () -> new DockerizedJavaExecHandleBuilder(extension,
                fileResolver,
                executor,
                buildCancellationToken,
                workerSemaphore,
                services.get(FileCollectionFactory.class),
                services.get(ObjectFactory.class),
                services.get(TemporaryFileProvider.class),
                services.get(PathToFileResolver.class)
        );

        return new DefaultWorkerProcessFactory(loggingManager,
                messagingServer,
                null,// defaultProcessBuilderFactory.workerImplementationFactory.classPathRegistry,
                null,//defaultProcessBuilderFactory.idGenerator,
                gradleUserHome,
                null,//defaultProcessBuilderFactory.workerImplementationFactory.temporaryFileProvider,
                javaExecHandleFactory,
                new DockerizedJvmVersionDetector(extension),
                null,// defaultProcessBuilderFactory.outputEventListener,
                memoryManager
        );
    }

    class MessageServer implements MessagingServer {
        IncomingConnector connector;
        ExecutorFactory executorFactory;

        MessageServer(IncomingConnector connector, ExecutorFactory executorFactory) {
            this.connector = connector;
            this.executorFactory = executorFactory;
        }

        @Override
        public ConnectionAcceptor accept(Action<ObjectConnection> action) {
            return new ConnectionAcceptorDelegate(connector.accept(new ConnectEventAction(action, executorFactory), true));
        }

    }

    static class ConnectEventAction implements Action<ConnectCompletion> {
        private final Action<ObjectConnection> action;
        private final ExecutorFactory executorFactory;

        ConnectEventAction(Action<ObjectConnection> action, ExecutorFactory executorFactory) {
            this.executorFactory = executorFactory;
            this.action = action;
        }

        @Override
        public void execute(ConnectCompletion completion) {
            action.execute(new MessageHubBackedObjectConnection(executorFactory, completion));
        }
    }

    static class ConnectionAcceptorDelegate implements ConnectionAcceptor {

        MultiChoiceAddress address;

        final ConnectionAcceptor delegate;

        ConnectionAcceptorDelegate(ConnectionAcceptor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Address getAddress() {
            synchronized (delegate) {
                if (address == null) {
                    try {
                        final List<InetAddress> remoteAddresses = NetworkInterface.networkInterfaces().filter(it -> {
                            try {
                                return it.isUp() && !it.isLoopback();
                            } catch (SocketException e) {
                                throw new RuntimeException(e);
                            }
                        }).map(it -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(it.getInetAddresses().asIterator(), Spliterator.ORDERED),
                                false).collect(Collectors.toList())).flatMap(Collection::stream).collect(Collectors.toList());
                        MultiChoiceAddress original = address;
                        address = new MultiChoiceAddress(original.getCanonicalAddress(), original.getPort(), remoteAddresses);
                    } catch (SocketException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return address;
        }

        @Override
        public void requestStop() {
            delegate.requestStop();
        }

        @Override
        public void stop() {
            delegate.stop();
        }
    }

}