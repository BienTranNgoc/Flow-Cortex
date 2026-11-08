import { createRefetchCoalescer } from './refetchCoalescer';

/** A refetch the test settles by hand, one call at a time. */
function controlledRefetch() {
  const settlers: Array<() => void> = [];
  const refetch = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        settlers.push(resolve);
      }),
  );
  return {
    refetch,
    settleNext: async () => {
      settlers.shift()?.();
      await new Promise((resolve) => setTimeout(resolve, 0));
    },
  };
}

describe('createRefetchCoalescer', () => {
  it('refetches at once when idle', () => {
    const { refetch } = controlledRefetch();
    const coalescer = createRefetchCoalescer(refetch);

    coalescer.request();

    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('turns ten events during a fetch into one trailing refetch', async () => {
    const { refetch, settleNext } = controlledRefetch();
    const coalescer = createRefetchCoalescer(refetch);
    coalescer.request();

    for (let i = 0; i < 10; i += 1) {
      coalescer.request();
    }
    expect(refetch).toHaveBeenCalledTimes(1);

    await settleNext();
    expect(refetch).toHaveBeenCalledTimes(2);
  });

  it('starts no further refetch when nothing arrived during the trailing one', async () => {
    const { refetch, settleNext } = controlledRefetch();
    const coalescer = createRefetchCoalescer(refetch);
    coalescer.request();
    coalescer.request();
    await settleNext();

    await settleNext();

    expect(refetch).toHaveBeenCalledTimes(2);
  });

  it('refetches at once again after it went quiet', async () => {
    const { refetch, settleNext } = controlledRefetch();
    const coalescer = createRefetchCoalescer(refetch);
    coalescer.request();
    await settleNext();

    coalescer.request();

    expect(refetch).toHaveBeenCalledTimes(2);
  });

  it('keeps going after a refetch that rejects', async () => {
    const refetch = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined);
    const coalescer = createRefetchCoalescer(refetch);

    coalescer.request();
    coalescer.request();
    await vi.waitFor(() => expect(refetch).toHaveBeenCalledTimes(2));
  });

  it('starts nothing after stop, not even the trailing refetch', async () => {
    const { refetch, settleNext } = controlledRefetch();
    const coalescer = createRefetchCoalescer(refetch);
    coalescer.request();
    coalescer.request();

    coalescer.stop();
    await settleNext();
    coalescer.request();

    expect(refetch).toHaveBeenCalledTimes(1);
  });
});
