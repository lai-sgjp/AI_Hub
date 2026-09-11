"""Compare upgrade snapshots without exposing their private contents."""


def verify_preserved(before, after):
    old_records, old_credentials = before
    new_records, new_credentials = after
    changed = sum(new_records.get(key) != value for key, value in old_records.items())
    if changed:
        raise ValueError(f'Upgrade removed or changed existing records: count={changed}')
    if old_credentials != new_credentials:
        raise ValueError('Upgrade changed encrypted credentials')
    return len(new_records.keys() - old_records.keys())
