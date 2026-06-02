package org.shreya.gpugrid.inventory;

public class GpuNotFoundException extends RuntimeException {

    public GpuNotFoundException(int id) {
        super("GPU not found: id=" + id);
    }
}
