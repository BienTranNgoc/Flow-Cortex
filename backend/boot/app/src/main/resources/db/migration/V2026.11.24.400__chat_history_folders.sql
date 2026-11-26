-- Chat history: member folders, a pinned flag and a folder on each conversation, and the diacritic-folding
-- function the conversation search uses. Owner ids carry no foreign key, as in chat_conversation.
-- Rolling back means a forward migration dropping the two indexes, the two columns, chat_search_fold and
-- chat_folder; folders and pins are lost, conversations are untouched.
CREATE TABLE chat_folder (
    id          uuid         PRIMARY KEY,
    owner_id    uuid         NOT NULL,
    name        varchar(60)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_folder_owner_created ON chat_folder (owner_id, created_at, id);

-- A NOT NULL DEFAULT constant column is a metadata-only change; folder_id starts NULL for every row, and
-- ON DELETE SET NULL returns a deleted folder's conversations to Recent even when a move races the delete.
ALTER TABLE chat_conversation
    ADD COLUMN pinned     boolean NOT NULL DEFAULT false,
    ADD COLUMN folder_id  uuid    REFERENCES chat_folder (id) ON DELETE SET NULL;

-- Plain CREATE INDEX: Flyway runs this file in one transaction, where CONCURRENTLY is not allowed.
CREATE INDEX idx_chat_conversation_owner_pinned
    ON chat_conversation (owner_id, last_activity_at DESC, id DESC) WHERE pinned;
CREATE INDEX idx_chat_conversation_owner_folder
    ON chat_conversation (owner_id, folder_id, last_activity_at DESC, id DESC) WHERE folder_id IS NOT NULL;

-- Lower-cases and strips Vietnamese diacritics without an extension. The first string lists the 134 precomposed
-- letters (a, e, i, o, u, y, d with every tone, circumflex, breve and horn form, both cases, and the stroked d)
-- and the second their base letters. The trailing U+0300 U+0301 U+0303 U+0309 U+0323 U+0302 U+0306 U+031B are the
-- combining marks of decomposed (NFD) text; they have no counterpart, so translate deletes them.
CREATE FUNCTION chat_search_fold(t text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT lower(translate(t,
        'àÀáÁảẢãÃạẠăĂằẰắẮẳẲẵẴặẶâÂầẦấẤẩẨẫẪậẬèÈéÉẻẺẽẼẹẸêÊềỀếẾểỂễỄệỆìÌíÍỉỈĩĨịỊòÒóÓỏỎõÕọỌôÔồỒốỐổỔỗỖộỘơƠờỜớỚởỞỡỠợỢùÙúÚủỦũŨụỤưƯừỪứỨửỬữỮựỰỳỲýÝỷỶỹỸỵỴđĐ'
            || U&'\0300\0301\0303\0309\0323\0302\0306\031B',
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaeeeeeeeeeeeeeeeeeeeeeeiiiiiiiiiioooooooooooooooooooooooooooooooooouuuuuuuuuuuuuuuuuuuuuuyyyyyyyyyydd'))
$$;
