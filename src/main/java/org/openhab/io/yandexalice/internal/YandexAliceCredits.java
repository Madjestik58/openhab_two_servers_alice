package org.openhab.io.yandexalice.internal;

/** Credentials are configured in openHAB and shared with callback requests. */
public class YandexAliceCredits {
    private static volatile String skillID = "";
    private static volatile String oAuth = "";

    public String getSkillID() {
        return skillID;
    }

    public String getoAuth() {
        return oAuth;
    }

    public void setSkillID(String value) {
        skillID = value == null ? "" : value;
    }

    public void setoAuth(String value) {
        oAuth = value == null ? "" : value;
    }
}
