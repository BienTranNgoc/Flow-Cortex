package com.flowcortex.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.profile.application.ProfileImageRules;
import com.flowcortex.profile.domain.ProfileImage.Slot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileImageRulesTest {

    @Test
    void squareAvatarWithinOnePixelFits() {
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 512, 512)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 512, 513)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 513, 512)).isTrue();
    }

    @Test
    void avatarOneByThreePixelsOffIsRefused() {
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 512, 514)).isFalse();
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 1500, 500)).isFalse();
    }

    @Test
    void avatarSideBoundsAreSixtyFourToTenTwentyFour() {
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 64, 64)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 63, 63)).isFalse();
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 1024, 1024)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.AVATAR, 1025, 1025)).isFalse();
    }

    @Test
    void coverWithinThreePixelsOfThreeToOneFits() {
        assertThat(ProfileImageRules.fits(Slot.COVER, 1500, 500)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.COVER, 1503, 500)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.COVER, 1497, 500)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.COVER, 1504, 500)).isFalse();
        assertThat(ProfileImageRules.fits(Slot.COVER, 1500, 600)).isFalse();
    }

    @Test
    void coverWidthBoundsAreThreeHundredToThreeThousand() {
        assertThat(ProfileImageRules.fits(Slot.COVER, 300, 100)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.COVER, 297, 99)).isFalse();
        assertThat(ProfileImageRules.fits(Slot.COVER, 3000, 1000)).isTrue();
        assertThat(ProfileImageRules.fits(Slot.COVER, 3003, 1001)).isFalse();
    }

    @Test
    void historyKeepsTheNewestThirtyAndReturnsTheRestOldestFirst() {
        List<Integer> newestFirst = new ArrayList<>();
        for (int age = 1; age <= 33; age++) {
            newestFirst.add(age);
        }

        assertThat(ProfileImageRules.beyondHistory(newestFirst)).containsExactly(33, 32, 31);
        assertThat(ProfileImageRules.beyondHistory(newestFirst.subList(0, 30))).isEmpty();
        assertThat(ProfileImageRules.beyondHistory(List.of())).isEmpty();
        assertThat(ProfileImageRules.HISTORY_LIMIT).isEqualTo(30);
    }
}
