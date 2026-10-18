/** "1,000" — credit amounts read the same everywhere in the app. */
export function formatCredits(amount: number): string {
  return amount.toLocaleString('en-US');
}
