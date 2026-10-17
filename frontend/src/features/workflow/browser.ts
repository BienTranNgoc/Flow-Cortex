/** Navigates this tab to `url`; its own module so tests can replace it, since jsdom's `location` cannot be spied on. */
export function openInCurrentTab(url: string): void {
  window.location.assign(url);
}

/** Saves `text` as a file named `fileName` without leaving the page. */
export function saveTextFile(fileName: string, text: string): void {
  const href = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = href;
  link.download = fileName;
  document.body.append(link);
  link.click();
  link.remove();
  // Revoking in the same task can cancel the download in some browsers before it has read the blob.
  setTimeout(() => {
    if (typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(href);
    }
  }, 1000);
}
