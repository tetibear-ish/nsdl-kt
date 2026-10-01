import { DropdownMenu } from "radix-ui";

export type GitCommit = { hash: string; subject: string };

export function HistoryDropdown({ commits = __GIT_HISTORY__ }: { commits?: GitCommit[] }) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button className="history-trigger" aria-label="History">History</button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content className="dropdown-content history-dropdown" align="start" sideOffset={6}>
          {commits.length === 0 ? (
            <DropdownMenu.Item className="dropdown-item history-entry" disabled>
              Commit history unavailable
            </DropdownMenu.Item>
          ) : commits.map((commit, index) => (
            <DropdownMenu.Item className="dropdown-item history-entry" key={`${commit.hash}-${index}`}>
              <code>{commit.hash}</code>
              <span>{commit.subject}</span>
              {index === 0 && <small>latest</small>}
            </DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
