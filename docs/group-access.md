# Group access

Groups allow users to share selected accounts for household budgets,
trips, gifts and other joint expenses.

## Membership

- A user can belong to multiple groups.
- The group creator becomes its owner and a member.
- Only the group owner can invite users or remove members.
- An invitation must be accepted before membership is created.
- Members can leave a group.
- The group owner cannot leave or remove themselves.
- Only the group owner can delete the group.

## Account sharing

Accounts remain owned by individual users.

- Only the account owner can share their account or change its permission.
- To share an account, its owner must belong to the group.
- Sharing applies to all members of that group.
- One account can be shared with multiple groups.
- The group owner cannot manage sharing of another user's account.
- Sharing includes the complete entry history, including earlier entries.

## Permissions

| Operation | READ | EDIT | Account owner |
|-----------|------|------|---------------|
| View the shared account and its entries | Yes | Yes | Yes |
| Create, edit or delete entries | No | Yes | Yes |
| Receive a transfer into the account | Yes | Yes | Yes |
| Send a transfer from the account | No | Yes | Yes |
| Manage account sharing | No | No | Yes |
| Delete the account | No | No | Yes, if it has no entries or transfers |

For transfers, the acting user must have EDIT access to the source
account and at least READ access to the destination account.

Moving an entry between accounts requires EDIT access to both accounts.

## Entries and categories

An entry belongs to the owner of its account for personal entry lists
and financial summaries, regardless of who created it.

When an entry moves to another account, it belongs to the new account owner.

Each newly registered user receives a personal set of default categories.
Unused personal categories can be deleted.

When creating an entry or selecting a different category, users can
choose only their own categories.

When editing an entry, users can keep its existing category even if
it belongs to another user or is a legacy global category.
This does not grant permission to select other categories
owned by that user.

A category used by an entry cannot be deleted until those entries
are moved to another category or deleted.

## Revoking access

- Revoking sharing does not delete the account or its financial records.
- Leaving a group removes membership and sharing of that user's accounts
  with the group.
- Removing a member has the same effect.
- Rejoining does not automatically restore previous account sharing.
- Deleting a group removes its memberships, invitations and sharing
  permissions, while preserving accounts, users and financial records.

Permissions granted through other groups remain effective.
For example, revoking EDIT in one group does not remove EDIT granted
through another group.

## API endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/groups` | List the current user's groups |
| POST | `/api/groups` | Create a group |
| GET | `/api/groups/{id}` | Read group details |
| GET | `/api/groups/{id}/members` | List members |
| POST | `/api/groups/{id}/invitations` | Invite a user |
| POST | `/api/groups/{id}/leave` | Leave a group |
| DELETE | `/api/groups/{groupId}/members/{userId}` | Remove a member |
| DELETE | `/api/groups/{id}` | Delete a group |
| GET | `/api/group-invitations` | List incoming invitations |
| POST | `/api/group-invitations/{id}/accept` | Accept an invitation |
| POST | `/api/group-invitations/{id}/decline` | Decline an invitation |
| GET | `/api/groups/{groupId}/accounts` | List shared accounts |
| PUT | `/api/groups/{groupId}/accounts/{accountId}` | Set sharing permission |
| DELETE | `/api/groups/{groupId}/accounts/{accountId}` | Revoke sharing |
| GET | `/api/groups/{groupId}/accounts/{accountId}/entries` | Read shared entries |

Create, edit and delete shared entries through the existing
`/api/entries` endpoints. Transfers use `/api/transfers`.

Requests use session authentication. State-changing requests also
require a CSRF token. See Swagger UI for request and response schemas.