package com.flowcortex.user.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

interface OAuthClientRepository extends JpaRepository<OAuthClient, String> {
}
