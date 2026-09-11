"""Package private local cards/lore as ignored Android assets. Does not edit source data."""
import argparse
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]
IMAGE_SUFFIXES = {'.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp'}

def relative_path(value, label):
    normalized = str(value).replace('\\', '/')
    path = pathlib.PurePosixPath(normalized)
    if not normalized or path.is_absolute() or any(part in ('', '.', '..') for part in path.parts):
        raise ValueError(f'{label} must be a relative path inside its source directory')
    return path

def bundle(config_file):
    config = json.loads(pathlib.Path(config_file).read_text(encoding='utf-8-sig'))
    character_root = pathlib.Path(config['charactersDir']).resolve(strict=True)
    world_root = pathlib.Path(config['worldDir']).resolve(strict=True)
    asset_subdir = str(config.get('assetSubdir', '')).strip('/\\')
    if asset_subdir:
        asset_subdir_path = relative_path(asset_subdir, 'assetSubdir')
        target = ROOT / 'app/src/main/assets/bundled' / pathlib.Path(*asset_subdir_path.parts)
    else:
        target = ROOT / 'app/src/main/assets/bundled'
    # Never recursively remove a symlink target or any caller-selected path.
    if target.resolve() != target or not target.resolve().is_relative_to(ROOT):
        raise ValueError('Bundled asset directory must remain inside this workspace')
    if any(source.is_relative_to(target) or target.is_relative_to(source) for source in (character_root, world_root)):
        raise ValueError('Source directories must not overlap the generated asset directory')
    configured_cards = config.get('characterCards')
    if configured_cards is not None and not isinstance(configured_cards, list):
        raise ValueError('characterCards must be an array of relative paths')
    if configured_cards is None:
        cards = sorted(source for source in character_root.rglob('*') if source.is_file() and (source.suffix.lower() == '.json' or (source.suffix.lower() == '.png' and len(source.relative_to(character_root).parts) == 1)))
    else:
        cards = []
        for card_path in configured_cards:
            relative = relative_path(card_path, 'characterCards path')
            source = (character_root / pathlib.Path(*relative.parts)).resolve(strict=True)
            if not source.is_file() or not source.is_relative_to(character_root) or source.suffix.lower() not in ('.json', '.png'):
                raise ValueError('characterCards must point to a JSON/PNG card inside charactersDir: ' + str(source))
            cards.append(source)
        cards = sorted(set(cards))
    card_set = set(cards)
    books = sorted(world_root.rglob('*.json'))
    if not cards or not books:
        raise ValueError('At least one JSON/PNG character card and lorebook are required')
    for file in cards:
        if file.suffix.lower() == '.json':
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
            if not source.is_file() or source.suffix.lower() not in ('.json', '.md', *IMAGE_SUFFIXES):
                continue
            if not source.resolve().is_relative_to(source_root):
                raise ValueError('Source symlinks outside supplied directories are not copied')
            relative = pathlib.PurePosixPath(prefix, *source.relative_to(source_root).parts).as_posix()
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            if prefix == 'characters' and source in card_set:
                manifest['cards'].append(relative)
            elif prefix == 'world' and source.suffix.lower() == '.json':
                manifest['books'].append(relative)
            elif source.suffix.lower() == '.md':
                manifest['documents'].append({'title': source.stem, 'path': relative})
    galleries = {}
    for card_name, images in config.get('characterGalleries', {}).items():
        card_relative = relative_path(card_name, 'characterGalleries card path')
        card_manifest_path = pathlib.PurePosixPath('characters', *card_relative.parts).as_posix()
        if card_manifest_path not in manifest['cards']:
            raise ValueError('Gallery references a card that is not packaged: ' + str(card_name))
        if not isinstance(images, list):
            raise ValueError('characterGalleries entries must be arrays: ' + str(card_name))
        packaged = []
        for image in images:
            if not isinstance(image, dict) or not image.get('path'):
                raise ValueError('Gallery image must contain a path: ' + str(card_name))
            image_relative = relative_path(image['path'], 'gallery image path')
            source = (character_root / pathlib.Path(*image_relative.parts)).resolve(strict=True)
            if not source.is_relative_to(character_root) or source.suffix.lower() not in IMAGE_SUFFIXES:
                raise ValueError('Gallery image must be a PNG/JPEG/WebP inside charactersDir: ' + str(source))
            packaged_path = pathlib.PurePosixPath('characters', *image_relative.parts).as_posix()
            packaged.append({'title': str(image.get('title', source.stem)), 'path': packaged_path, 'mimeType': IMAGE_SUFFIXES[source.suffix.lower()]})
        galleries[card_manifest_path] = packaged
    manifest['galleries'] = galleries
    (target / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'cards': len(manifest['cards']), 'books': len(manifest['books']), 'documents': len(manifest['documents']), 'asset_directory': str(target)}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True, help='Path to ignored local JSON config')
    bundle(parser.parse_args().config)
