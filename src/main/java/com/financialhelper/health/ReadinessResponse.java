package com.financialhelper.health;

import java.util.Map;

public record ReadinessResponse(String status, Map<String, String> dependencies) { }
