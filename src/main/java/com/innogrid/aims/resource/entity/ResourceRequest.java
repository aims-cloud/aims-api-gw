package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "resource_requests")
public class ResourceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "catalog_id", nullable = false)
    private CatalogItem catalogItem;

    private Long requesterId;

    @Column(length = 100)
    private String requesterName;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(length = 300)
    private String reviewComment;

    private OffsetDateTime requestedAt;
    private OffsetDateTime reviewedAt;

    @PrePersist
    void onCreate() { this.requestedAt = OffsetDateTime.now(); }

    protected ResourceRequest() {}

    public ResourceRequest(CatalogItem catalogItem, Long requesterId,
                           String requesterName, String reason) {
        this.catalogItem = catalogItem;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public CatalogItem getCatalogItem() { return catalogItem; }
    public Long getRequesterId() { return requesterId; }
    public String getRequesterName() { return requesterName; }
    public String getReason() { return reason; }
    public RequestStatus getStatus() { return status; }
    public String getReviewComment() { return reviewComment; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }

    public void approve(String comment) {
        this.status = RequestStatus.APPROVED;
        this.reviewComment = comment;
        this.reviewedAt = OffsetDateTime.now();
    }

    public void reject(String comment) {
        this.status = RequestStatus.REJECTED;
        this.reviewComment = comment;
        this.reviewedAt = OffsetDateTime.now();
    }

    public enum RequestStatus {
        PENDING, APPROVED, REJECTED
    }
}
