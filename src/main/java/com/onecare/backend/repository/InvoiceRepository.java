package com.onecare.backend.repository;

import com.onecare.backend.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>,
        JpaSpecificationExecutor<Invoice> {

    boolean existsByAppointmentAppointmentId(Long appointmentId);

    boolean existsByDispenseDispenseId(Long dispenseId);

    /** Consultation invoice with its lines (dispense re-billing guard). */
    @EntityGraph(attributePaths = {"items"})
    Optional<Invoice> findByAppointmentAppointmentId(Long appointmentId);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    /** Detail view / reprint loads lines and payments with the invoice. */
    @Override
    @EntityGraph(attributePaths = {"items", "payments"})
    Optional<Invoice> findById(Long id);
}
