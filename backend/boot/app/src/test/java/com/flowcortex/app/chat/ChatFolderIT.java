package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Folders and moving a conversation into one over HTTP: names, the cap, ownership and the delete. */
@ChatIt
class ChatFolderIT extends ChatItSupport {

    private static final String FOLDERS = "/api/v1/chat/folders";

    record NameRequest(String name) {
    }

    record MoveRequest(UUID folderId) {
    }

    record Folder(UUID id, String name, int conversationCount, Instant createdAt) {
    }

    record FolderList(List<Folder> items) {
    }

    record Item(UUID id, String title, Instant lastActivityAt, Instant createdAt, boolean pinned, UUID folderId) {
    }

    record ConversationList(List<Item> items) {
    }

    @Test
    void creatingTrimsTheNameAndAnswers201WithAnEmptyCountThenItRenames() {
        String token = login(member("folder-create").email());

        ResponseEntity<Folder> created = create(token, "  Ads  ");
        ResponseEntity<Folder> renamed = rename(token, created.getBody().id(), "Campaigns");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().name()).isEqualTo("Ads");
        assertThat(created.getBody().conversationCount()).isZero();
        assertThat(created.getBody().createdAt()).isNotNull();
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().id()).isEqualTo(created.getBody().id());
        assertThat(renamed.getBody().name()).isEqualTo("Campaigns");
    }

    @Test
    void aBlankOrOverLongNameIsRefusedOnTheNameFieldWhenCreatingAndRenaming() {
        String token = login(member("folder-name").email());
        UUID folder = create(token, "Kept").getBody().id();

        for (String bad : new String[] {"   ", "a".repeat(61)}) {
            ResponseEntity<Problem> onCreate = restTemplate.exchange(FOLDERS, HttpMethod.POST,
                    new HttpEntity<>(new NameRequest(bad), bearer(token)), Problem.class);
            ResponseEntity<Problem> onRename = restTemplate.exchange(FOLDERS + "/" + folder, HttpMethod.PUT,
                    new HttpEntity<>(new NameRequest(bad), bearer(token)), Problem.class);

            for (ResponseEntity<Problem> response : List.of(onCreate, onRename)) {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
                assertThat(response.getBody().errors()).containsKey("name");
            }
        }
        assertThat(create(token, "a".repeat(60)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM chat_folder WHERE id = ?", String.class, folder))
                .isEqualTo("Kept");
    }

    @Test
    void theListIsInCreationOrderWithCountsThatIncludeAPinnedConversation() {
        UserView owner = member("folder-list");
        String token = login(owner.email());
        UUID ads = create(token, "Ads").getBody().id();
        UUID blog = create(token, "Blog").getBody().id();
        UUID empty = create(token, "Empty").getBody().id();
        UUID pinned = conversation(owner.id(), "Pinned one");
        UUID plain = conversation(owner.id(), "Plain one");
        UUID inBlog = conversation(owner.id(), "In blog");
        jdbcTemplate.update("UPDATE chat_conversation SET pinned = true WHERE id = ?", pinned);
        move(token, pinned, ads);
        move(token, plain, ads);
        move(token, inBlog, blog);

        List<Folder> folders = restTemplate.exchange(FOLDERS, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                FolderList.class).getBody().items();

        assertThat(folders).extracting(Folder::id).containsExactly(ads, blog, empty);
        assertThat(folders).extracting(Folder::conversationCount).containsExactly(2, 1, 0);
    }

    @Test
    void theListHoldsOnlyTheCallersFolderAndCountsOnlyTheirConversations() {
        UserView owner = member("folder-scope");
        UserView other = member("folder-scope-other");
        String token = login(owner.email());
        UUID mine = create(token, "Mine").getBody().id();
        create(login(other.email()), "Theirs");
        move(token, conversation(owner.id(), "Filed"), mine);

        List<Folder> folders = restTemplate.exchange(FOLDERS, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                FolderList.class).getBody().items();

        assertThat(folders).extracting(Folder::name).containsExactly("Mine");
        assertThat(folders.getFirst().conversationCount()).isEqualTo(1);
    }

    @Test
    void deletingAFolderKeepsItsConversationsInRecentAndKeepsThemPinned() {
        UserView owner = member("folder-delete");
        String token = login(owner.email());
        UUID ads = create(token, "Ads").getBody().id();
        UUID plain = conversation(owner.id(), "Plain");
        UUID pinned = conversation(owner.id(), "Pinned");
        jdbcTemplate.update("UPDATE chat_conversation SET pinned = true WHERE id = ?", pinned);
        move(token, plain, ads);
        move(token, pinned, ads);

        ResponseEntity<Void> deleted = restTemplate.exchange(FOLDERS + "/" + ads, HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);
        List<Item> recent = restTemplate.exchange(BASE + "?pinned=exclude&folder=none", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ConversationList.class).getBody().items();

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rows("chat_folder", "id", ads)).isZero();
        assertThat(folderOf(plain)).isNull();
        assertThat(folderOf(pinned)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT pinned FROM chat_conversation WHERE id = ?", Boolean.class,
                pinned)).isTrue();
        assertThat(recent).extracting(Item::id).containsExactly(plain);
    }

    @Test
    void aConversationMovesIntoAFolderAndBackToRecent() {
        UserView owner = member("folder-move");
        String token = login(owner.email());
        UUID ads = create(token, "Ads").getBody().id();
        UUID id = conversation(owner.id(), "Moving");
        Instant activity = jdbcTemplate.queryForObject("SELECT last_activity_at FROM chat_conversation WHERE id = ?",
                Instant.class, id);

        ResponseEntity<Item> in = move(token, id, ads);
        List<Item> underAds = restTemplate.exchange(BASE + "?folder=" + ads, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ConversationList.class).getBody().items();
        ResponseEntity<Item> out = move(token, id, null);
        List<Item> recent = restTemplate.exchange(BASE + "?pinned=exclude&folder=none", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ConversationList.class).getBody().items();

        assertThat(in.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(in.getBody().folderId()).isEqualTo(ads);
        assertThat(underAds).extracting(Item::id).containsExactly(id);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody().folderId()).isNull();
        assertThat(recent).extracting(Item::id).containsExactly(id);
        assertThat(jdbcTemplate.queryForObject("SELECT last_activity_at FROM chat_conversation WHERE id = ?",
                Instant.class, id)).isEqualTo(activity);
    }

    @Test
    void movingIntoAFolderAnotherCallDeletedIs404AndLeavesTheConversationWhereItWas() {
        UserView owner = member("folder-gone");
        String token = login(owner.email());
        UUID kept = create(token, "Kept").getBody().id();
        UUID gone = create(token, "Gone").getBody().id();
        UUID id = conversation(owner.id(), "Stays");
        move(token, id, kept);
        restTemplate.exchange(FOLDERS + "/" + gone, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);

        ResponseEntity<Problem> response = restTemplate.exchange(BASE + "/" + id + "/folder", HttpMethod.PUT,
                new HttpEntity<>(new MoveRequest(gone), bearer(token)), Problem.class);

        assertFolderNotFound(response);
        assertThat(folderOf(id)).isEqualTo(kept);
    }

    @Test
    void anotherMembersFolderCannotBeRenamedDeletedOrMovedIntoAndStaysUnchanged() {
        UserView owner = member("folder-owner");
        UserView intruder = member("folder-intruder");
        UUID folder = create(login(owner.email()), "Private").getBody().id();
        String token = login(intruder.email());
        UUID mine = conversation(intruder.id(), "Mine");
        UUID missing = UUID.randomUUID();

        for (UUID id : List.of(folder, missing)) {
            assertFolderNotFound(restTemplate.exchange(FOLDERS + "/" + id, HttpMethod.PUT,
                    new HttpEntity<>(new NameRequest("Mine now"), bearer(token)), Problem.class));
            assertFolderNotFound(restTemplate.exchange(FOLDERS + "/" + id, HttpMethod.DELETE,
                    new HttpEntity<>(bearer(token)), Problem.class));
            assertFolderNotFound(restTemplate.exchange(BASE + "/" + mine + "/folder", HttpMethod.PUT,
                    new HttpEntity<>(new MoveRequest(id), bearer(token)), Problem.class));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM chat_folder WHERE id = ?", String.class, folder))
                .isEqualTo("Private");
        assertThat(folderOf(mine)).isNull();
    }

    @Test
    void anotherMembersConversationCannotBeMovedAndStaysUnchanged() {
        UserView owner = member("folder-conv-owner");
        UserView intruder = member("folder-conv-intruder");
        UUID foreign = conversation(owner.id(), "Private");
        UUID theirFolder = create(login(owner.email()), "Theirs").getBody().id();
        move(login(owner.email()), foreign, theirFolder);
        String token = login(intruder.email());
        UUID ownFolder = create(token, "Own").getBody().id();

        for (UUID target : new UUID[] {ownFolder, null}) {
            ResponseEntity<Problem> response = restTemplate.exchange(BASE + "/" + foreign + "/folder",
                    HttpMethod.PUT, new HttpEntity<>(new MoveRequest(target), bearer(token)), Problem.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-conversation-not-found"));
            assertThat(response.getBody().code()).isEqualTo("chat-conversation-not-found");
        }
        assertThat(folderOf(foreign)).isEqualTo(theirFolder);
    }

    @Test
    void atTheLimitACreateIs409ChatFolderLimitAndTheMemberStillHoldsTheLimit() {
        UserView owner = member("folder-limit");
        String token = login(owner.email());
        seedFolders(owner.id(), 100);

        ResponseEntity<Problem> response = restTemplate.exchange(FOLDERS, HttpMethod.POST,
                new HttpEntity<>(new NameRequest("One too many"), bearer(token)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-folder-limit"));
        assertThat(response.getBody().code()).isEqualTo("chat-folder-limit");
        assertThat(response.getBody().detail()).contains("100");
        assertThat(rows("chat_folder", "owner_id", owner.id())).isEqualTo(100);
    }

    @Test
    void twoConcurrentCreatesAtNinetyNineLeaveOneCreatedOneRefusedAndExactlyOneHundred()
            throws InterruptedException, ExecutionException {
        UserView owner = member("folder-race");
        String token = login(owner.email());
        seedFolders(owner.id(), 99);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpStatus>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String name = "Racer " + i;
                calls.add(pool.submit(() -> {
                    start.await();
                    return HttpStatus.valueOf(restTemplate.exchange(FOLDERS, HttpMethod.POST,
                            new HttpEntity<>(new NameRequest(name), bearer(token)), String.class)
                            .getStatusCode().value());
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> call : calls) {
                statuses.add(call.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows("chat_folder", "owner_id", owner.id())).isEqualTo(100);
    }

    @Test
    void creatingAndMovingStillWorkWithTheConsoleSwitchOff() {
        UserView owner = member("folder-off");
        String token = login(owner.email());
        UUID id = conversation(owner.id(), "Kept");
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");

        ResponseEntity<Folder> created = create(token, "Ads");
        ResponseEntity<Item> moved = move(token, id, created.getBody().id());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().folderId()).isEqualTo(created.getBody().id());
    }

    @Test
    void theFolderRoutesNeedASignedInMember() {
        assertThat(restTemplate.getForEntity(FOLDERS, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange(BASE + "/" + UUID.randomUUID() + "/folder", HttpMethod.PUT,
                new HttpEntity<>(new MoveRequest(null)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Folder> create(String token, String name) {
        return restTemplate.exchange(FOLDERS, HttpMethod.POST, new HttpEntity<>(new NameRequest(name), bearer(token)),
                Folder.class);
    }

    private ResponseEntity<Folder> rename(String token, UUID id, String name) {
        return restTemplate.exchange(FOLDERS + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new NameRequest(name), bearer(token)), Folder.class);
    }

    private ResponseEntity<Item> move(String token, UUID conversationId, UUID folderId) {
        return restTemplate.exchange(BASE + "/" + conversationId + "/folder", HttpMethod.PUT,
                new HttpEntity<>(new MoveRequest(folderId), bearer(token)), Item.class);
    }

    private void seedFolders(UUID ownerId, int count) {
        jdbcTemplate.update("INSERT INTO chat_folder (id, owner_id, name) "
                + "SELECT gen_random_uuid(), ?, 'Seed ' || g FROM generate_series(1, ?) g", ownerId, count);
    }

    private UUID folderOf(UUID conversationId) {
        return jdbcTemplate.queryForObject("SELECT folder_id FROM chat_conversation WHERE id = ?", UUID.class,
                conversationId);
    }

    private void assertFolderNotFound(ResponseEntity<Problem> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-folder-not-found"));
        assertThat(response.getBody().code()).isEqualTo("chat-folder-not-found");
    }
}
