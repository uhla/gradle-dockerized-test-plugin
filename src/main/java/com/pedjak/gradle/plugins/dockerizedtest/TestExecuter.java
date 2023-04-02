package com.pedjak.gradle.plugins.dockerizedtest;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.common.collect.ImmutableSet;

import org.glassfish.jersey.internal.guava.Sets;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.tasks.testing.*;
import org.gradle.api.internal.tasks.testing.detection.DefaultTestClassScanner;
import org.gradle.api.internal.tasks.testing.detection.TestFrameworkDetector;
import org.gradle.api.internal.tasks.testing.processors.MaxNParallelTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.RestartEveryNTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.TestMainAction;
import org.gradle.internal.Factory;
import org.gradle.internal.time.Clock;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.process.internal.worker.WorkerProcessFactory;

public class TestExecuter implements org.gradle.api.internal.tasks.testing.TestExecuter<JvmTestExecutionSpec> {
    private final WorkerProcessFactory workerFactory;
    private final ActorFactory actorFactory;
    private final ModuleRegistry moduleRegistry;
    private final BuildOperationExecutor buildOperationExecutor;
    private final WorkerLeaseService workerLeaseService;
    private final Clock clock;
    private TestClassProcessor processor;

    public TestExecuter(WorkerProcessFactory workerFactory, ActorFactory actorFactory, ModuleRegistry moduleRegistry,
                        BuildOperationExecutor buildOperationExecutor, Clock clock, WorkerLeaseService workerLeaseService) {
        this.workerFactory = workerFactory;
        this.actorFactory = actorFactory;
        this.moduleRegistry = moduleRegistry;
        this.buildOperationExecutor = buildOperationExecutor;
        this.clock = clock;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void execute(final JvmTestExecutionSpec testExecutionSpec, TestResultProcessor testResultProcessor) {
        final TestFramework testFramework = testExecutionSpec.getTestFramework();
        final WorkerTestClassProcessorFactory testInstanceFactory = testFramework.getProcessorFactory();
        final Set<File> classpath = ImmutableSet.copyOf(testExecutionSpec.getClasspath());

        final Factory<TestClassProcessor> forkingProcessorFactory = () -> new ForkingTestClassProcessor(workerFactory, testInstanceFactory,
                testExecutionSpec.getJavaForkOptions(),
                classpath, testFramework.getWorkerConfigurationAction(), moduleRegistry);
        Factory<TestClassProcessor> reforkingProcessorFactory = () -> new RestartEveryNTestClassProcessor(forkingProcessorFactory,
                testExecutionSpec.getForkEvery());

        processor = new MaxNParallelTestClassProcessor(testExecutionSpec.getMaxParallelForks(),
                reforkingProcessorFactory, actorFactory);

        final FileTree testClassFiles = testExecutionSpec.getCandidateClassFiles();

        Runnable detector;
        if (testExecutionSpec.isScanForTestClasses() && testFramework.getDetector() != null) {
            System.out.println(" 55555555555 testFramework detector");
            TestFrameworkDetector testFrameworkDetector = testExecutionSpec.getTestFramework().getDetector();
            testFrameworkDetector.setTestClasses(testExecutionSpec.getTestClassesDirs().getFiles());
            testFrameworkDetector.setTestClasspath(classpath);
            detector = new DefaultTestClassScanner(testClassFiles, testFrameworkDetector, processor);
        } else {
            System.out.println(" 66666666666 testFramework no detector");
            detector = new DefaultTestClassScanner(testClassFiles, null, processor);
        }

        Object testTaskOperationId;

        try {
            testTaskOperationId = buildOperationExecutor.getCurrentOperation().getParentId();
        } catch (Exception e) {
            testTaskOperationId = UUID.randomUUID();
        }

        new TestMainAction(detector, processor, testResultProcessor, workerLeaseService, clock, testTaskOperationId,
                "Gradle Test Run " + testExecutionSpec.getIdentityPath()).run();
    }

    public void stopNow() {
        if (processor != null) {
            processor.stopNow();
        }
    }
}
