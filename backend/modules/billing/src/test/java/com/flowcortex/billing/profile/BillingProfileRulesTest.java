package com.flowcortex.billing.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.profile.BillingProfileRules.Invalid;
import com.flowcortex.billing.profile.BillingProfileRules.Outcome;
import com.flowcortex.billing.profile.BillingProfileRules.Valid;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BillingProfileRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PERSONAL = """
            {"type":"personal","legalName":"Nguyễn Văn A","personalIdNumber":"001099012345","address":"12 Lý Thường Kiệt"}
            """;
    private static final String BUSINESS = """
            {"type":"business","legalName":"Công ty ABC","taxCode":"0101243150","address":"1 Tràng Tiền"}
            """;

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static ProfileFields stored(String body) {
        return validFields(BillingProfileRules.create(json(body)));
    }

    private static ProfileFields validFields(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Valid.class);
        return ((Valid) outcome).fields();
    }

    private static Invalid invalid(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Invalid.class);
        return (Invalid) outcome;
    }

    @Test
    void aValidPersonalProfileIsBuilt() {
        ProfileFields fields = stored(PERSONAL);

        assertThat(fields).isEqualTo(new ProfileFields("personal", "Nguyễn Văn A", null, "001099012345",
                "12 Lý Thường Kiệt", null));
    }

    @Test
    void aValidBusinessAndAHouseholdWithABranchTaxCodeAreBuilt() {
        assertThat(stored(BUSINESS).taxCode()).isEqualTo("0101243150");
        ProfileFields household = stored("""
                {"type":"household","legalName":"Hộ KD B","taxCode":" 0101243150-001 ","address":"2 Hàng Bài"}
                """);
        assertThat(household.taxCode()).isEqualTo("0101243150-001");
    }

    @Test
    void stringsAreTrimmed() {
        ProfileFields fields = stored("""
                {"type":" business ","legalName":"  Công ty ABC ","taxCode":" 0101243150 ","address":" 1 Tràng Tiền ",
                 "email":" a@b.vn "}
                """);

        assertThat(fields).isEqualTo(new ProfileFields("business", "Công ty ABC", "0101243150", null,
                "1 Tràng Tiền", "a@b.vn"));
    }

    @Test
    void aBusinessWithoutATaxCodeOrWithAShortOneNamesTheTaxCode() {
        assertThat(invalid(BillingProfileRules.create(json(
                "{\"type\":\"business\",\"legalName\":\"Công ty ABC\",\"address\":\"1 Tràng Tiền\"}"))).errors())
                .containsOnlyKeys("taxCode");
        assertThat(invalid(BillingProfileRules.create(json(
                "{\"type\":\"business\",\"legalName\":\"Công ty ABC\",\"address\":\"1\",\"taxCode\":\"01012431\"}")))
                .errors()).containsOnlyKeys("taxCode");
    }

    @Test
    void aPersonalIdOfElevenDigitsNamesThePersonalId() {
        Invalid refused = invalid(BillingProfileRules.create(json(
                PERSONAL.replace("001099012345", "00109901234"))));

        assertThat(refused.errors()).containsOnlyKeys("personalIdNumber");
    }

    @Test
    void aPersonalProfileWithoutAnAddressNamesTheAddress() {
        Invalid refused = invalid(BillingProfileRules.create(json(
                "{\"type\":\"personal\",\"legalName\":\"A\",\"personalIdNumber\":\"001099012345\"}")));

        assertThat(refused.errors()).containsOnlyKeys("address");
    }

    @Test
    void aNameOfEmojiOnlyOrOverFourHundredCharactersNamesTheName() {
        assertThat(invalid(BillingProfileRules.create(json(PERSONAL.replace("Nguyễn Văn A", "🎉")))).errors())
                .containsOnlyKeys("legalName");
        assertThat(invalid(BillingProfileRules.create(json(
                PERSONAL.replace("Nguyễn Văn A", "x".repeat(401))))).errors()).containsOnlyKeys("legalName");
        assertThat(BillingProfileRules.create(json(PERSONAL.replace("Nguyễn Văn A", "x".repeat(400)))))
                .isInstanceOf(Valid.class);
    }

    @Test
    void anAddressOverFiveHundredCharactersNamesTheAddress() {
        assertThat(invalid(BillingProfileRules.create(json(
                PERSONAL.replace("12 Lý Thường Kiệt", "x".repeat(501))))).errors()).containsOnlyKeys("address");
    }

    @Test
    void aBadEmailOrAnUnknownTypeIsNamed() {
        assertThat(invalid(BillingProfileRules.create(json(
                PERSONAL.replace("}", ",\"email\":\"a@\"}")))).errors()).containsOnlyKeys("email");
        assertThat(invalid(BillingProfileRules.create(json(
                PERSONAL.replace("\"type\":\"personal\"", "\"type\":\"company\"")))).errors())
                .containsOnlyKeys("type");
    }

    @Test
    void aTaxCodeSentWithAPersonalProfileAndAPersonalIdSentWithABusinessAreDropped() {
        ProfileFields personal = stored(PERSONAL.replace("}", ",\"taxCode\":\"0101243150\"}"));
        ProfileFields business = stored(BUSINESS.replace("}", ",\"personalIdNumber\":\"001099012345\"}"));

        assertThat(personal.taxCode()).isNull();
        assertThat(business.personalIdNumber()).isNull();
    }

    @Test
    void aWrongJsonTypeNamesItsField() {
        assertThat(invalid(BillingProfileRules.create(json(PERSONAL.replace("\"Nguyễn Văn A\"", "5")))).errors())
                .containsOnlyKeys("legalName");
    }

    @Test
    void theDefaultFlagIsReadSeparatelyAndMustBeABoolean() {
        assertThat(((Valid) BillingProfileRules.create(json(PERSONAL.replace("}", ",\"isDefault\":true}"))))
                .makeDefault()).isTrue();
        assertThat(((Valid) BillingProfileRules.create(json(PERSONAL.replace("}", ",\"isDefault\":false}"))))
                .makeDefault()).isFalse();
        assertThat(((Valid) BillingProfileRules.create(json(PERSONAL))).makeDefault()).isFalse();
        assertThat(invalid(BillingProfileRules.create(json(PERSONAL.replace("}", ",\"isDefault\":\"yes\"}"))))
                .errors()).containsOnlyKeys("isDefault");
    }

    @Test
    void anEmptyPatchKeepsTheStoredProfile() {
        ProfileFields before = stored(PERSONAL);

        assertThat(validFields(BillingProfileRules.merge(before, json("{}")))).isEqualTo(before);
    }

    @Test
    void movingAPersonalProfileToBusinessWithoutATaxCodeNamesTheTaxCode() {
        Invalid refused = invalid(BillingProfileRules.merge(stored(PERSONAL), json("{\"type\":\"business\"}")));

        assertThat(refused.errors()).containsOnlyKeys("taxCode");
    }

    @Test
    void movingABusinessProfileToPersonalClearsTheTaxCode() {
        ProfileFields merged = validFields(BillingProfileRules.merge(stored(BUSINESS),
                json("{\"type\":\"personal\",\"personalIdNumber\":\"001099012345\"}")));

        assertThat(merged.type()).isEqualTo("personal");
        assertThat(merged.taxCode()).isNull();
        assertThat(merged.personalIdNumber()).isEqualTo("001099012345");
    }

    @Test
    void clearingARequiredFieldNamesIt() {
        Invalid refused = invalid(BillingProfileRules.merge(stored(PERSONAL), json("{\"address\":null}")));

        assertThat(refused.errors()).containsOnlyKeys("address");
    }

    @Test
    void anOptionalFieldCanBeClearedAndABlankOneIsCleared() {
        ProfileFields withEmail = stored(PERSONAL.replace("}", ",\"email\":\"a@b.vn\"}"));

        assertThat(validFields(BillingProfileRules.merge(withEmail, json("{\"email\":null}"))).email()).isNull();
        assertThat(validFields(BillingProfileRules.merge(withEmail, json("{\"email\":\"  \"}"))).email()).isNull();
    }
}
