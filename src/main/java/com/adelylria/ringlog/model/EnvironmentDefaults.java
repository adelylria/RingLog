package com.adelylria.ringlog.model;

import java.util.List;
import com.adelylria.ringlog.model.view.EnvironmentOption;

/** Original choices shared by migrations and portable catalog reconciliation. */
public final class EnvironmentDefaults {
    private static final List<EnvironmentOption> OPTIONS = List.of(
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000001", EnvironmentCategory.CLOUDS, "0%", true, 0),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000002", EnvironmentCategory.CLOUDS, "1-25%", true, 1),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000003", EnvironmentCategory.CLOUDS, "25-75%", true, 2),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000004", EnvironmentCategory.CLOUDS, "75-100%", true, 3),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000005", EnvironmentCategory.RAIN, "Nula", true, 0),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000006", EnvironmentCategory.RAIN, "Débil", true, 1),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000007", EnvironmentCategory.RAIN, "Moderada", true, 2),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000008", EnvironmentCategory.RAIN, "Fuerte", true, 3),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000009", EnvironmentCategory.THERMAL_SENSATION, "Mucho frío", true, 0),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000010", EnvironmentCategory.THERMAL_SENSATION, "Frío", true, 1),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000011", EnvironmentCategory.THERMAL_SENSATION, "Templado", true, 2),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000012", EnvironmentCategory.THERMAL_SENSATION, "Calor", true, 3),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000013", EnvironmentCategory.THERMAL_SENSATION, "Mucho calor", true, 4),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000014", EnvironmentCategory.WIND, "Nulo", true, 0),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000015", EnvironmentCategory.WIND, "Débil", true, 1),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000016", EnvironmentCategory.WIND, "Moderado", true, 2),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000017", EnvironmentCategory.WIND, "Fuerte", true, 3),
            new EnvironmentOption(0, "ed8a9136-748e-4d64-a748-000000000018", EnvironmentCategory.WIND, "Muy fuerte", true, 4)
    );
    private EnvironmentDefaults() { }
    public static List<EnvironmentOption> options() { return OPTIONS; }
}
