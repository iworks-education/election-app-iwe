package com.aws.serverless;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codecommit.*;
import software.amazon.awscdk.services.codepipeline.*;
import software.amazon.awscdk.services.codepipeline.actions.*;

import java.util.*;

import static software.amazon.awscdk.services.codebuild.LinuxBuildImage.AMAZON_LINUX_2_2;


public class PipelineStack extends Stack {
    public PipelineStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public PipelineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Bucket artifactsBucket = new Bucket(this, "ArtifactsBucket");

        IRepository codeRepo = Repository.fromRepositoryName(this, "AppRepository", "election-app");

        Pipeline pipeline = new Pipeline(this, "Pipeline", PipelineProps.builder()
                .artifactBucket(artifactsBucket).build());

        Artifact sourceOutput = new Artifact("sourceOutput");

        CodeCommitSourceAction codeCommitSource = new CodeCommitSourceAction(CodeCommitSourceActionProps.builder()
                .actionName("CodeCommit_Source")
                .repository(codeRepo)
                .output(sourceOutput)
                .build());

        pipeline.addStage(StageOptions.builder()
                .stageName("Source")
                .actions(Collections.singletonList(codeCommitSource))
                .build());

        // Declare build output as artifacts
        Artifact buildOutput = new Artifact("buildOutput");

        // Declare a new CodeBuild project
        PipelineProject pipelineProject = new PipelineProject(this, "Build", PipelineProjectProps.builder()
                .environment(BuildEnvironment.builder()
                        .buildImage(AMAZON_LINUX_2_2).build())
                .environmentVariables(Collections.singletonMap("PACKAGE_BUCKET",
                        BuildEnvironmentVariable.builder()
                                .value(artifactsBucket.getBucketName())
                                .build()))
                .cache(Cache.bucket(new Bucket(this, "CacheBucket")))
                .buildSpec(BuildSpec.fromSourceFilename("election-api/buildspec.yml"))
                .build());

        IRole role = pipelineProject.getRole();

        if (role != null) {
            IManagedPolicy managedPolicy = ManagedPolicy.fromAwsManagedPolicyName("AmazonCodeGuruReviewerFullAccess");
            role.addManagedPolicy(managedPolicy);
        }

        // Add the build stage to our pipeline
        CodeBuildAction buildAction = new CodeBuildAction(CodeBuildActionProps.builder()
                .actionName("Build")
                .project(pipelineProject)
                .input(sourceOutput)
                .outputs(Collections.singletonList(buildOutput))
                .build());

        pipeline.addStage(StageOptions.builder()
                .stageName("Build")
                .actions(Collections.singletonList(buildAction))
                .build());

        // Deploy stage
        CloudFormationCreateReplaceChangeSetAction createChangeSet = new CloudFormationCreateReplaceChangeSetAction(CloudFormationCreateReplaceChangeSetActionProps.builder()
                .actionName("CreateChangeSet")
                .templatePath(buildOutput.atPath("packaged.yaml"))
                .stackName("election-app")
                .adminPermissions(true)
                .changeSetName("election-app-changeset")
                .runOrder(1)
                .build());

        CloudFormationExecuteChangeSetAction executeChangeSet = new CloudFormationExecuteChangeSetAction(CloudFormationExecuteChangeSetActionProps.builder()
                .actionName("Deploy")
                .stackName("election-app")
                .changeSetName("election-app-changeset")
                .runOrder(2)
                .build());

        pipeline.addStage(StageOptions.builder()
                .stageName("Dev")
                .actions(Arrays.asList(createChangeSet, executeChangeSet))
                .build());
    }
}