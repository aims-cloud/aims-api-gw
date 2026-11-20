package com.innogrid.aims.resource.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.resource.entity.ResourceRequest;

public interface ResourceRequestRepository extends JpaRepository<ResourceRequest, Long> {

    List<ResourceRequest> findByStatusOrderByRequestedAtDesc(ResourceRequest.RequestStatus status);

    List<ResourceRequest> findAllByOrderByRequestedAtDesc();
}
