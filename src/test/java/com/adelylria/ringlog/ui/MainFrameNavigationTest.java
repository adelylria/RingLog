package com.adelylria.ringlog.ui;

public final class MainFrameNavigationTest {

    private MainFrameNavigationTest() {
    }

    public static void diaryIsTheInitialScreen() {
        require(
                MainFrame.HOME.equals(MainFrame.initialScreen()),
                "RingLog should open in Diario"
        );
    }

    public static void detailHasItsOwnRoute() {
        require(
                "CAPTURE_DETAIL".equals(MainFrame.CAPTURE_DETAIL),
                "Capture detail should have a dedicated route"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        diaryIsTheInitialScreen();
        detailHasItsOwnRoute();
        System.out.println("MainFrameNavigationTest: PASS");
    }
}
