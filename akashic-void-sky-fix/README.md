# Akashic Void Sky Fix

Coremod **do cliente** (Forge 1.7.10) que tira o céu "fechando" quando o jogador cai abaixo do
mundo e, em mundos `FLAT` (como o void `Spawn` do `AkashicVoidGenerator`), também a neblina preta.

## O que o vanilla faz

- `RenderGlobal.renderSky` calcula `alturaDoJogador - world.getHorizon()`. Quando o valor é
  negativo, o jogo desenha uma caixa preta em volta do céu, e ela sobe conforme você desce.
  Num mundo `FLAT` o horizonte é y=0, então o "quadrado" aparece a partir de y≈-64.
- `EntityRenderer.updateFogColor` multiplica a cor da neblina por `(y × getVoidFogYFactor())²`
  quando esse valor fica abaixo de 1. Num mundo `FLAT` o fator é 1.0, então abaixo de y=1 tudo
  fica preto.
- O Dynamic Surroundings refaz esse mesmo cálculo no evento `FogColors`
  (`BiomeFogColorCalculator.applyPlayerEffects`).

## O que o patch muda

Ele insere uma chamada estática logo depois de cada uma dessas contas:

| Classe / método | Instrução | Hook |
|---|---|---|
| `RenderGlobal.func_72714_a` (renderSky) | `getHorizon()` → `DSUB` | `skyHeightAboveHorizon`: abaixo de y=0 o valor fica igual ao de y=0 |
| `EntityRenderer.func_78466_h` (updateFogColor) | `getVoidFogYFactor()` → `DMUL` | `voidFogBrightness`: em mundo `FLAT`, valor abaixo de 1 vira 1 |
| `BiomeFogColorCalculator.applyPlayerEffects` (Dynamic Surroundings) | idem | `voidFogBrightness` |

- **Céu (qualquer tipo de mundo):** abaixo de y=0 o céu fica como em y=0. Num mundo `FLAT` a caixa
  preta não aparece; num mundo normal ela fica abaixo do horizonte, como na altura da bedrock.
  De y=0 para cima nada muda.
- **Neblina (só `FLAT`):** sem escurecimento por profundidade. Em mundos normais o escurecimento
  começa em y=32 e é o clima de caverna profunda do vanilla, então fica como está.
- A cegueira (Blindness) continua escurecendo a tela, porque o jogo aplica ela depois do hook.
- Cada alvo precisa ser encontrado exatamente uma vez. Se não for (por exemplo, outra versão do
  mod), a classe fica intacta e o log mostra `[Akashic Void Sky Fix] REFUSED patch ...`.
- Para desligar sem tirar o jar: `-Dakashic.voidsky.disable=true`.

Conferi os mixins do pack que mexem nesses métodos: o Galacticraft (`getSkyColor`/`getFogColor`),
o Et Futurum (TAIL do `renderSky`) e o Hodgepodge (`isPotionActive` de visão noturna). Nenhum
deles usa as instruções alteradas aqui.

## Instalação

Coloque `dist/Akashic-Void-Sky-Fix-1.0.1.jar` na pasta `mods/` **do modpack dos jogadores**,
porque o efeito é desenhado pelo cliente. No servidor ele é opcional e não faz nada.

No log do cliente (`logs/fml-client-latest.log`) devem aparecer:

```
[Akashic Void Sky Fix] v1.0.1 armed (client side; no-op on dedicated servers).
[Akashic Void Sky Fix] Patched net.minecraft.client.renderer.RenderGlobal -> VoidSkyHooks.skyHeightAboveHorizon
[Akashic Void Sky Fix] Patched net.minecraft.client.renderer.EntityRenderer -> VoidSkyHooks.voidFogBrightness
[Akashic Void Sky Fix] Patched org.blockartistry.mod.DynSurround.client.fog.BiomeFogColorCalculator -> VoidSkyHooks.voidFogBrightness
```

E, a cada mundo em que o jogador entra, uma linha com o que o patch viu, por exemplo:

```
[Akashic Void Sky Fix] world dim=2 provider=net.minecraft.world.WorldProviderSurface terrainType=flat horizon=0.0 voidFogYFactor=1.0 -> sky below y=0: fixed, depth fog: disabled (FLAT)
```

## Build e teste

```
./build.sh "<asm-debug-all-5.0.3.jar>:<launchwrapper-1.12.jar>:<forge-1.7.10-universal.jar>:<client-srg.jar>" <client-srg.jar> [DynamicSurroundings.jar]
```

`client-srg.jar` é o `client.jar` 1.7.10 da Mojang remapeado com SpecialSource e o `joined.srg`
do MCP 1.7.10. O `VoidSkyTransformerTest` aplica o transformer nas classes reais e confere três
coisas: que o hook ficou exatamente entre as instruções esperadas, que todos os métodos passam
no `BasicVerifier` do ASM, e que classes fora da lista não são alteradas.
