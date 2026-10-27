package com.ownsudo.repository;

import com.ownsudo.entity.GatewayRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GatewayRouteRepository extends JpaRepository<GatewayRoute, String> {
    List<GatewayRoute> findByHealthyTrue();
}