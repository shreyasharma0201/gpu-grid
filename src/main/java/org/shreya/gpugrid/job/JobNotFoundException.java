package org.shreya.gpugrid.job;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(int id) {
        super("Job not found: id=" + id);
    }
}
