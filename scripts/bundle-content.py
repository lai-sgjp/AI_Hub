"""Package private local cards/lore as ignored Android assets. Does not edit source data."""
import argparse
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]

def bundle(config_file):
    config = json.loads(pathlib.Path(config_file).read_text(encoding='utf-8-sig'))
    character_root = pathlib.Path(config['charactersDir']).resolve(strict=True)
    world_root = pathlib.Path(config['worldDir']).resolve(strict=True)
    target = ROOT / 'app/src/main/assets/bundled'
    # Never recursively remove a symlink target or any caller-selected path.
    if target.resolve() != target or not target.resolve().is_relative_to(ROOT):
        raise ValueError('Bundled asset directory must remain inside this workspace')
    if any(source.is_relative_to(target) or target.is_relative_to(source) for source in (character_root, world_root)):
        raise ValueError('Source directories must not overlap the generated asset directory')
    cards = sorted(character_root.rglob('*.json'))
    books = sorted(world_root.rglob('*.json'))
    if not cards or not books:
        raise ValueError('At least one JSON character card and lorebook are required')
    for file in cards:
        card = json.loads(file.read_text(encoding='utf-8-sig'))
        if not card.get('data', card).get('name'):
            raise ValueError('Character card is missing its name: ' + str(file))
    for file in books:
        json.loads(file.read_text(encoding='utf-8-sig'))
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    manifest = {key: config[key] for key in ('id', 'worldName', 'worldDescription', 'playerName', 'playerDescription', 'preferredCharacters') if key in config}
    manifest.update(cards=[], books=[], documents=[])
    for source_root, prefix in ((character_root, 'characters'), (world_root, 'world')):
        for source in sorted(source_root.rglob('*')):
            if not source.is_file() or source.suffix.lower() not in ('.json', '.md', '.png'):
                continue
            if not source.resolve().is_relative_to(source_root):
                raise ValueError('Source symlinks outside supplied directories are not copied')
            relative = pathlib.PurePosixPath(prefix, *source.relative_to(source_root).parts).as_posix()
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            if source.suffix.lower() == '.json':
                manifest['cards' if prefix == 'characters' else 'books'].append(relative)
            elif source.suffix.lower() == '.md':
                manifest['documents'].append({'title': source.stem, 'path': relative})
    (target / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'cards': len(manifest['cards']), 'books': len(manifest['books']), 'documents': len(manifest['documents']), 'asset_directory': str(target)}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True, help='Path to ignored local JSON config')
    bundle(parser.parse_args().config)
