package com.example;

record ItemCenterMass(boolean valid, double x, double y, double z, int count) {
    static final ItemCenterMass EMPTY = new ItemCenterMass(false, 0.0, 0.0, 0.0, 0);
}
