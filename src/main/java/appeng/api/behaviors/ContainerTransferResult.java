package appeng.api.behaviors;

/**
 * Outcome of a {@link ContainerTransfer} operation.
 */
public final class ContainerTransferResult {

    private static final ContainerTransferResult NONE = new ContainerTransferResult(0, 0);

    private final long amount;
    private final int containers;

    private ContainerTransferResult(long amount, int containers) {
        this.amount = amount;
        this.containers = containers;
    }

    public static ContainerTransferResult none() {
        return NONE;
    }

    static ContainerTransferResult of(long amount, int containers) {
        return amount <= 0 || containers <= 0 ? NONE : new ContainerTransferResult(amount, containers);
    }

    /**
     * @return the amount that was moved between the containers and the storage.
     */
    public long getAmount() {
        return this.amount;
    }

    /**
     * @return how many containers were filled or drained, including a partially processed one.
     */
    public int getContainers() {
        return this.containers;
    }

    public boolean isEmpty() {
        return this.amount <= 0;
    }
}
