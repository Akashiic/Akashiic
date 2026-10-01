# Pack de compatibilidade `atm10-8.2`

- Nome: All the Mods 10 8.2
- Minecraft 1.21.1 (protocolo 767), NeoForge 21.1.251
- Origem: ServerFiles-8.2.zip (1218983508 bytes, sha256 8f1ef6e6969924bc18a7194782ea9266f7a0bde41141dd94776b5225316d3278)
- Mods carregados no servidor: 502
- Canais NeoForge do servidor: 2550 (1930 obrigatórios)
- SERVER configs: 290
- Registries sincronizados: 52 (41 que o Paper não envia; 10 do Paper estendidos com 971 entradas)
- Registries com tags: 31
- BlockStates globais: 1548078; propriedades extras em blocos vanilla: 16

## Registries que o Paper não envia (anexados inteiros após os registries do Paper)

- `supplementaries:placeable_books`: 4 entradas
- `compactmachines:room_templates`: 8 entradas
- `neovitae:altar_tier`: 6 entradas
- `neovitae:ritual_layout`: 36 entradas
- `neovitae:sentient_upgrades`: 46 entradas
- `neovitae:sigil_type`: 16 entradas
- `enderio:conduit`: 17 entradas
- `irons_spellbooks:upgrade_orb_type`: 14 entradas
- `eternal_starlight:biome_data`: 25 entradas
- `eternal_starlight:boarwarf_type`: 3 entradas
- `eternal_starlight:astral_golem_material`: 2 entradas
- `eternal_starlight:ent_variant`: 5 entradas
- `eternal_starlight:shimmer_lacewing_variant`: 2 entradas
- `eternal_starlight:seeker_variant`: 2 entradas
- `eternal_starlight:seeds_launcher_ammo_type`: 8 entradas
- `eternal_starlight:alloy_furnace_coolant`: 25 entradas
- `eternal_starlight:painting_variant`: 38 entradas
- `mekanism:robit_skin`: 13 entradas
- `create_aquatic_ambitions:conduit_effect`: 24 entradas
- `aether:moa_type`: 3 entradas
- `twilight:restrictions`: 9 entradas
- `twilight:magic_paintings`: 5 entradas
- `twilight:dwarf_rabbit_variant`: 3 entradas
- `twilight:tiny_bird_variant`: 4 entradas
- `twilight:travellers_modifiers`: 24 entradas
- `computercraft:turtle_upgrade`: 23 entradas
- `computercraft:pocket_upgrade`: 10 entradas
- `computercraft:turtle_overlay`: 2 entradas
- `irons_jewelry:pattern`: 16 entradas
- `irons_jewelry:material`: 38 entradas
- `irons_jewelry:part`: 27 entradas
- `create:potato_projectile/type`: 34 entradas
- `moonlight:map_marker`: 35 entradas
- `moonlight:soft_fluid`: 462 entradas
- `forbidden_arcanus:hephaestus_forge/ritual`: 43 entradas
- `forbidden_arcanus:enhancer/definition`: 7 entradas
- `forbidden_arcanus:research/knowledge`: 3 entradas
- `forbidden_arcanus:research/constellation`: 1 entradas
- `forbidden_arcanus:residue_type`: 11 entradas
- `forbidden_arcanus:magic_circle`: 3 entradas
- `forbidden_arcanus:item_modifier`: 6 entradas

## Registries do Paper estendidos

Só as entradas que o vanilla 1.21.1 não tem são anexadas, depois das do Paper: os ids numéricos do Paper continuam valendo e overrides de entradas vanilla ficam com a definição do Paper. `minecraft:enchantment` é substituído inteiro quando `compatibility-pack-replace-enchantment-registry=true`.

- `minecraft:worldgen/biome`: 310 entradas no servidor; 246 anexadas; 5 overrides vanilla mantidos do Paper
- `minecraft:trim_pattern`: 23 entradas no servidor; 5 anexadas; 0 overrides vanilla mantidos do Paper
- `minecraft:trim_material`: 53 entradas no servidor; 43 anexadas; 0 overrides vanilla mantidos do Paper
- `minecraft:wolf_variant`: 10 entradas no servidor; 1 anexadas; 9 overrides vanilla mantidos do Paper
- `minecraft:painting_variant`: 109 entradas no servidor; 59 anexadas; 0 overrides vanilla mantidos do Paper
- `minecraft:dimension_type`: 31 entradas no servidor; 27 anexadas; 1 overrides vanilla mantidos do Paper
- `minecraft:damage_type`: 361 entradas no servidor; 314 anexadas; 0 overrides vanilla mantidos do Paper
- `minecraft:banner_pattern`: 137 entradas no servidor; 94 anexadas; 0 overrides vanilla mantidos do Paper
- `minecraft:enchantment`: 139 entradas no servidor; 97 anexadas; 3 overrides vanilla mantidos do Paper
- `minecraft:jukebox_song`: 104 entradas no servidor; 85 anexadas; 0 overrides vanilla mantidos do Paper

## O que mods acrescentam a blocos vanilla (decide o mapa de BlockState)

- `minecraft:creeper_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:creeper_wall_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:dragon_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:dragon_wall_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:note_block`: propriedade `instrument` ganhou valores `kobolediator,aptrgangr,draugr`
- `minecraft:piglin_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:piglin_wall_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:player_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:player_wall_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:skeleton_skull` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:skeleton_wall_skull` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:water_cauldron` + propriedade nova `boiling` (padrão `false`; valores `true,false`)
- `minecraft:wither_skeleton_skull` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:wither_skeleton_wall_skull` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:zombie_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
- `minecraft:zombie_wall_head` + propriedade nova `waterlogged` (padrão `false`; valores `true,false`)
