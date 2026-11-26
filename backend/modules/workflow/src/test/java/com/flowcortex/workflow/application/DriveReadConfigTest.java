package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code drive.read} config: exactly one folder source, an optional bounded file count, nothing unknown. */
class DriveReadConfigTest {

    private static final String LINK = "https://drive.google.com/drive/folders/1AbCdEfGhIjK?usp=sharing";

    @Test
    void aFolderLinkParsesToItsIdWithTheGivenMaximum() {
        DriveReadConfig.Result result = DriveReadConfig.parse("{\"folderId\":\"" + LINK + "\",\"maxFiles\":10}");

        assertThat(result.fault()).isNull();
        assertThat(result.config()).isEqualTo(new DriveReadConfig("1AbCdEfGhIjK", false, 10));
    }

    @Test
    void aBareIdIsAFolder() {
        assertThat(DriveReadConfig.parse("{\"folderId\":\"1AbCdEfGhIjK\"}").config())
                .isEqualTo(new DriveReadConfig("1AbCdEfGhIjK", false, 20));
    }

    @Test
    void theFolderCanComeFromTheInputAndTheMaximumDefaultsToTwenty() {
        DriveReadConfig.Result result = DriveReadConfig.parse("{\"folderFromInput\":true}");

        assertThat(result.fault()).isNull();
        assertThat(result.config()).isEqualTo(new DriveReadConfig(null, true, 20));
    }

    @Test
    void theBoundsOfTheMaximumAreAccepted() {
        assertThat(DriveReadConfig.parse("{\"folderFromInput\":true,\"maxFiles\":1}").config().maxFiles()).isEqualTo(1);
        assertThat(DriveReadConfig.parse("{\"folderFromInput\":true,\"maxFiles\":50}").config().maxFiles())
                .isEqualTo(50);
    }

    @Test
    void aFolderIdBesideAFalseSwitchIsStillAFolderId() {
        assertThat(DriveReadConfig.parse("{\"folderId\":\"1AbCdEfGhIjK\",\"folderFromInput\":false}").fault()).isNull();
    }

    @Test
    void bothSourcesOrNeitherAreRefused() {
        assertThat(DriveReadConfig.fault("{\"folderId\":\"1AbCdEfGhIjK\",\"folderFromInput\":true}"))
                .contains("folderId").contains("folderFromInput");
        assertThat(DriveReadConfig.fault("{}")).contains("folderId").contains("folderFromInput");
        assertThat(DriveReadConfig.fault("{\"folderFromInput\":false}")).contains("folderFromInput");
        assertThat(DriveReadConfig.fault("{\"maxFiles\":5}")).contains("folderId");
    }

    @Test
    void aLinkThatIsNotAFolderIsRefusedNamingTheField() {
        assertThat(DriveReadConfig.fault("{\"folderId\":\"https://drive.google.com/file/d/1XyZ/view\"}"))
                .contains("folderId");
        assertThat(DriveReadConfig.fault("{\"folderId\":\"https://drive.google.com/drive/my-drive\"}"))
                .contains("folderId");
        assertThat(DriveReadConfig.fault("{\"folderId\":\"short\"}")).contains("folderId");
        assertThat(DriveReadConfig.fault("{\"folderId\":\"\"}")).contains("folderId");
        assertThat(DriveReadConfig.fault("{\"folderId\":12345678901}")).contains("folderId");
        assertThat(DriveReadConfig.fault("{\"folderId\":null}")).contains("folderId");
    }

    @Test
    void aMaximumOutsideOneToFiftyOrNotAnIntegerIsRefusedNamingTheField() {
        for (String maxFiles : new String[] {"0", "51", "\"10\"", "10.5", "-1", "true", "null", "3000000000"}) {
            assertThat(DriveReadConfig.fault("{\"folderFromInput\":true,\"maxFiles\":" + maxFiles + "}"))
                    .as(maxFiles).contains("maxFiles");
        }
    }

    @Test
    void aSwitchThatIsNotABooleanIsRefused() {
        assertThat(DriveReadConfig.fault("{\"folderFromInput\":\"true\"}")).contains("folderFromInput");
    }

    @Test
    void anUnknownKeyIsRefusedByName() {
        assertThat(DriveReadConfig.fault("{\"folderFromInput\":true,\"maxFile\":5}")).contains("maxFile'");
    }

    @Test
    void aConfigThatIsNotAnObjectIsRefused() {
        assertThat(DriveReadConfig.fault("[]")).isEqualTo("config must be a JSON object");
        assertThat(DriveReadConfig.fault("{\"folderFromInput\":true")).isEqualTo("config is not valid JSON");
        assertThat(DriveReadConfig.fault("{\"folderFromInput\":true} x")).isEqualTo("config is not valid JSON");
        assertThat(DriveReadConfig.fault(null)).isEqualTo("config is required");
        assertThat(DriveReadConfig.fault("  ")).isEqualTo("config is required");
    }

    @Test
    void aRefusedConfigParsesToNoConfig() {
        DriveReadConfig.Result result = DriveReadConfig.parse("{}");

        assertThat(result.config()).isNull();
        assertThat(result.fault()).isNotNull();
    }
}
