package com.onecare.backend.service;

import com.onecare.backend.dto.request.MedicineRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.response.MedicineResponse;

import java.util.List;

public interface MedicineService {

    MedicineResponse createMedicine(MedicineRequest request);

    List<MedicineResponse> findAllMedicines();

    MedicineResponse findMedicineById(Long id);

    MedicineResponse updateMedicine(
            Long id,
            MedicineRequest request
    );

    void deleteMedicine(Long id);

    MedicineResponse updateStock(
            Long id,
            StockUpdateRequest request
    );

    List<MedicineResponse> findLowStockMedicines();

    List<MedicineResponse> findNearExpiryMedicines();

    List<MedicineResponse> findAvailableMedicines();

    void quarantineExpiredMedicines();
}