package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateExternalDispensingRequest;
import com.onecare.backend.dto.request.VerifyExternalDispensingRequest;
import com.onecare.backend.dto.response.ExternalDispensingResponse;

import java.util.List;

public interface ExternalDispensingService {

    ExternalDispensingResponse createExternalDispensing(CreateExternalDispensingRequest request);

    ExternalDispensingResponse verifyExternalDispensing(Long dispenseId, VerifyExternalDispensingRequest request);

    ExternalDispensingResponse completeExternalDispensing(Long dispenseId);

    List<ExternalDispensingResponse> findAllExternalDispensing(String status);

    ExternalDispensingResponse findExternalDispensingById(Long dispenseId);
}
