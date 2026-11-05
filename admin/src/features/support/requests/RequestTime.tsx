const VIETNAM_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Ho_Chi_Minh',
  hour: '2-digit',
  minute: '2-digit',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hourCycle: 'h23',
});

/** "HH:mm dd/MM/yyyy" in Vietnam time, whatever the browser's zone. */
export function RequestTime({ value }: Readonly<{ value: string }>) {
  const parts = Object.fromEntries(
    VIETNAM_TIME.formatToParts(new Date(value)).map((part) => [part.type, part.value]),
  );
  return (
    <time dateTime={value}>
      {`${parts.hour}:${parts.minute} ${parts.day}/${parts.month}/${parts.year}`}
    </time>
  );
}
