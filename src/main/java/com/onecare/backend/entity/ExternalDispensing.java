package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.onecare.backend.enums.DeliveryMethod;
import com.onecare.backend.enums.Status;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "external_dispensing")
public class ExternalDispensing {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "dispense_id")
  private Long dispenseId;

  @ManyToOne
  @JoinColumn(name = "prescription_id") // NULL: OTC / no prescription sheet
  private Prescription prescription;

  /**
   * Medicines handed over in this event; empty for a whole-prescription handover.
   */
  @OneToMany(mappedBy = "dispensing", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<DispensingItem> items = new ArrayList<>();

  /** Attaches a handed-over medicine to this event (both sides of the link). */
  public void addItem(DispensingItem item) {
    item.setDispensing(this);
    items.add(item);
  }

  @Column(name = "verification_method", nullable = false, length = 100)
  private String verificationMethod;

  @Column(name = "verification_status", nullable = false, length = 30)
  private String verificationStatus = "PENDING";

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @Column(name = "retry_count", nullable = false)
  private Integer retryCount = 0;

  @Column(name = "audit_log_id")
  private Long auditLogId;

  @Column(name = "dispensed_at", nullable = false)
  private LocalDateTime dispensedAt;

  @Column(name = "patient_id", nullable = true, length = 10)
  private Long patientId;

  @Column(name = "dispense_date", nullable = false)
  private LocalDateTime dispenseDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private Status status;

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_method", nullable = false)
  private DeliveryMethod deliveryMethod;

}
