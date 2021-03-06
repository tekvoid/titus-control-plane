/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.ext.jobvalidator.s3;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.netflix.compute.validator.protogen.ComputeValidator;
import com.netflix.compute.validator.protogen.ComputeValidator.ValidationFailure;
import com.netflix.titus.api.jobmanager.JobAttributes;
import com.netflix.titus.api.jobmanager.model.job.JobDescriptor;
import com.netflix.titus.api.jobmanager.model.job.LogStorageInfos;
import com.netflix.titus.common.model.admission.AdmissionValidator;
import com.netflix.titus.common.model.admission.ValidatorMetrics;
import com.netflix.titus.common.model.sanitizer.ValidationError;
import com.netflix.titus.common.runtime.TitusRuntime;
import com.netflix.titus.common.util.StringExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class JobS3LogLocationValidator implements AdmissionValidator<JobDescriptor> {

    private static final Logger logger = LoggerFactory.getLogger(JobS3LogLocationValidator.class);

    private static final String VALIDATOR_PATH = "/titusS3AccessValidator";

    private static final String REASON_ACCESS_DENIED = "accessDenied";

    private static final long RETRY_COUNT = 3;

    private final ReactorValidationServiceClient validationClient;
    private final String defaultBucket;
    private final String defaultPathPrefix;
    private final Function<String, String> iamRoleArnResolver;
    private final Supplier<ValidationError.Type> validationErrorTypeProvider;
    private final Supplier<Boolean> enabledSupplier;
    private final ValidatorMetrics metrics;

    public JobS3LogLocationValidator(ReactorValidationServiceClient validationClient,
                                     String defaultBucket,
                                     String defaultPathPrefix,
                                     Function<String, String> iamRoleArnResolver,
                                     Supplier<ValidationError.Type> validationErrorTypeProvider,
                                     Supplier<Boolean> enabledSupplier,
                                     TitusRuntime titusRuntime) {
        this.validationClient = validationClient;
        this.defaultBucket = defaultBucket;
        this.defaultPathPrefix = defaultPathPrefix;
        this.iamRoleArnResolver = iamRoleArnResolver;
        this.validationErrorTypeProvider = validationErrorTypeProvider;
        this.enabledSupplier = enabledSupplier;
        this.metrics = new ValidatorMetrics(JobS3LogLocationValidator.class.getSimpleName(), titusRuntime.getRegistry());
    }

    @Override
    public Mono<Set<ValidationError>> validate(JobDescriptor jobDescriptor) {
        if (!enabledSupplier.get()) {
            metrics.incrementValidationSkipped(ValidatorMetrics.REASON_DISABLED);
            return Mono.just(Collections.emptySet());
        }

        LogStorageInfos.S3Bucket s3BucketInfo = LogStorageInfos.findCustomS3Bucket(jobDescriptor).orElse(null);
        String customPrefix = LogStorageInfos.findCustomPathPrefix(jobDescriptor).orElse(null);
        if (s3BucketInfo == null && customPrefix == null) {
            metrics.incrementValidationSkipped(ValidatorMetrics.REASON_NOT_APPLICABLE);
            return Mono.just(Collections.emptySet());
        }

        String bucketName = s3BucketInfo == null ? defaultBucket : s3BucketInfo.getBucketName();
        String pathPrefix = LogStorageInfos.buildPathPrefix(
                customPrefix == null ? defaultPathPrefix : LogStorageInfos.buildPathPrefix(customPrefix, defaultPathPrefix),
                VALIDATOR_PATH
        );

        String iamRole = jobDescriptor.getContainer().getSecurityProfile().getIamRole();
        // This condition should never happen, but we are adding this check here just in case.
        if (StringExt.isEmpty(iamRole)) {
            metrics.incrementValidationError(bucketName, REASON_ACCESS_DENIED);
            return Mono.just(Collections.singleton(new ValidationError("iamRole", "IAM role not set")));
        }
        iamRole = iamRoleArnResolver.apply(iamRole);

        Mono<Set<ValidationError>> action = validationClient.validateS3BucketAccess(
                ComputeValidator.S3BucketAccessValidationRequest.newBuilder()
                        .setBucket(bucketName)
                        .setBucketPrefix(pathPrefix)
                        .setIamRole(iamRole)
                        .build()
        ).map(result -> {
            if (result.getResultCase() == ComputeValidator.S3BucketAccessValidationResponse.ResultCase.FAILURES) {
                List<ValidationFailure> failures = result.getFailures().getFailuresList();
                if (!failures.isEmpty()) {
                    metrics.incrementValidationError(bucketName, REASON_ACCESS_DENIED);
                    return toValidationError(failures);
                }
            }
            metrics.incrementValidationSuccess(bucketName);
            return Collections.emptySet();
        });
        return action.retry(RETRY_COUNT)
                .onErrorMap(error -> {
                    logger.warn("S3 validation failure: {}", error.getMessage());
                    logger.debug("Stack trace", error);
                    metrics.incrementValidationError(bucketName, error.getClass().getSimpleName());

                    return new IllegalArgumentException(String.format("S3 bucket validation error: bucket=%s, pathPrefix=%s, error=%s",
                            bucketName,
                            pathPrefix,
                            error.getMessage()
                    ), error);
                });
    }

    @Override
    public ValidationError.Type getErrorType() {
        return validationErrorTypeProvider.get();
    }

    private Set<ValidationError> toValidationError(List<ValidationFailure> failures) {
        Set<ValidationError> result = new HashSet<>();

        failures.forEach(failure -> {
            result.add(new ValidationError(
                    JobAttributes.JOB_CONTAINER_ATTRIBUTE_S3_BUCKET_NAME,
                    String.format("Access denied: errorCode=%s, errorMessage=%s", failure.getErrorCode(), failure.getErrorMessage())
            ));
        });

        return result;
    }
}
