package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DomainFoundationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createsWorkspaceUserDocumentRoomAndMemberships() {
        Fixture fixture = fixture();

        assertThat(getMap("/api/v1/workspaces/" + fixture.workspaceId())).containsEntry("status", "ACTIVE");
        assertThat(getMap("/api/v1/users/" + fixture.ownerId())).containsEntry("status", "ACTIVE");
        assertThat(getMap("/api/v1/documents/" + fixture.documentId())).containsEntry("status", "ACTIVE");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId())).containsEntry("status", "OPEN");

        ResponseEntity<Object[]> memberships = restTemplate.getForEntity(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/memberships",
                Object[].class);
        assertThat(memberships.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(memberships.getBody()).hasSize(3);
    }

    @Test
    void duplicateKeysAreRejectedClearly() {
        String suffix = UUID.randomUUID().toString();
        Map<String, Object> workspaceBody = Map.of("workspaceKey", "dup-ws-" + suffix, "name", "Workspace");
        postMap("/api/v1/workspaces", workspaceBody);
        ResponseEntity<Map> duplicateWorkspace = restTemplate.postForEntity(baseUrl + "/api/v1/workspaces", workspaceBody, Map.class);
        assertThat(duplicateWorkspace.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateWorkspace.getBody()).containsEntry("code", "WORKSPACE_ALREADY_EXISTS");

        Map<String, Object> userBody = Map.of("externalUserKey", "dup-user-" + suffix, "displayName", "User");
        postMap("/api/v1/users", userBody);
        ResponseEntity<Map> duplicateUser = restTemplate.postForEntity(baseUrl + "/api/v1/users", userBody, Map.class);
        assertThat(duplicateUser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateUser.getBody()).containsEntry("code", "USER_ALREADY_EXISTS");

        Fixture fixture = fixture();
        Map<String, Object> documentBody = Map.of("documentKey", "same-doc", "title", "Doc");
        postMap("/api/v1/workspaces/" + fixture.workspaceId() + "/documents", documentBody);
        ResponseEntity<Map> duplicateDocument = restTemplate.postForEntity(
                baseUrl + "/api/v1/workspaces/" + fixture.workspaceId() + "/documents", documentBody, Map.class);
        assertThat(duplicateDocument.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateDocument.getBody()).containsEntry("code", "DOCUMENT_ALREADY_EXISTS");

        Map<String, Object> roomBody = Map.of("roomKey", "same-room", "roomType", "DOCUMENT");
        postMap("/api/v1/workspaces/" + fixture.workspaceId() + "/documents/" + fixture.documentId() + "/rooms", roomBody);
        ResponseEntity<Map> duplicateRoom = restTemplate.postForEntity(
                baseUrl + "/api/v1/workspaces/" + fixture.workspaceId() + "/documents/" + fixture.documentId() + "/rooms",
                roomBody,
                Map.class);
        assertThat(duplicateRoom.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateRoom.getBody()).containsEntry("code", "ROOM_ALREADY_EXISTS");
    }

    @Test
    void invalidRelationshipsAndUnknownEntitiesReturnClearErrors() {
        Fixture fixture = fixture();
        UUID unknown = UUID.randomUUID();

        ResponseEntity<Map> unknownWorkspace = restTemplate.postForEntity(
                baseUrl + "/api/v1/workspaces/" + unknown + "/documents",
                Map.of("documentKey", "doc", "title", "Doc"),
                Map.class);
        assertThat(unknownWorkspace.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownWorkspace.getBody()).containsEntry("code", "WORKSPACE_NOT_FOUND");

        Map<String, Object> otherWorkspace = postMap("/api/v1/workspaces",
                Map.of("workspaceKey", "other-" + unknown, "name", "Other"));
        ResponseEntity<Map> wrongRelationship = restTemplate.postForEntity(
                baseUrl + "/api/v1/workspaces/" + otherWorkspace.get("id") + "/documents/" + fixture.documentId() + "/rooms",
                Map.of("roomKey", "bad-room", "roomType", "DOCUMENT"),
                Map.class);
        assertThat(wrongRelationship.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongRelationship.getBody()).containsEntry("code", "INVALID_DOCUMENT_WORKSPACE");

        assertThat(restTemplate.getForEntity(baseUrl + "/api/v1/workspaces/" + unknown, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity(baseUrl + "/api/v1/users/" + unknown, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity(baseUrl + "/api/v1/documents/" + unknown, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + unknown, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
