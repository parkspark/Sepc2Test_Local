interface Props {
  message: string;
}

export function NeedsHumanBanner({ message }: Props) {
  return (
    <div className="needs-human-banner">
      <strong>사람 확인이 필요하다</strong>
      <p>{message}</p>
    </div>
  );
}
