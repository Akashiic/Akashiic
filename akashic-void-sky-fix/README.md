# Akashic Void Sky Fix

Coremod **do cliente** (Forge 1.7.10) que tira o céu "fechando" e a neblina preta quando o jogador
cai abaixo do mundo num mundo `FLAT`, como o mundo void `Spawn` gerado pelo `AkashicVoidGenerator`.

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
| `RenderGlobal.func_72714_a` (renderSky) | `getHorizon()` → `DSUB` | `skyHeightAboveHorizon`: valor negativo vira 0 |
| `EntityRenderer.func_78466_h` (updateFogColor) | `getVoidFogYFactor()` → `DMUL` | `voidFogBrightness`: valor abaixo de 1 vira 1 |
| `BiomeFogColorCalculator.applyPlayerEffects` (Dynamic Surroundings) | idem | `voidFogBrightness` |

- Só age quando o mundo do cliente é `FLAT`, a mesma regra que o vanilla usa para o horizonte em y=0.
- Acima do mundo nada muda.
- A cegueira (Blindness) continua escurecendo a tela, porque o jogo aplica ela depois do hook.
- Cada alvo precisa ser encontrado exatamente uma vez. Se não for (por exemplo, outra versão do
  mod), a classe fica intacta e o log mostra `[Akashic Void Sky Fix] REFUSED patch ...`.
- Para desligar sem tirar o jar: `-Dakashic.voidsky.disable=true`.

Conferi os mixins do pack que mexem nesses métodos: o Galacticraft (`getSkyColor`/`getFogColor`),
o Et Futurum (TAIL do `renderSky`) e o Hodgepodge (`isPotionActive` de visão noturna). Nenhum
deles usa as instruções alteradas aqui.

## Instalação

Coloque `dist/Akashic-Void-Sky-Fix-1.0.0.jar` na pasta `mods/` **do modpack dos jogadores**,
porque o efeito é desenhado pelo cliente. No servidor ele é opcional e não faz nada.

No log do cliente devem aparecer:

```
[Akashic Void Sky Fix] Patched net.minecraft.client.renderer.RenderGlobal -> VoidSkyHooks.skyHeightAboveHorizon
[Akashic Void Sky Fix] Patched net.minecraft.client.renderer.EntityRenderer -> VoidSkyHooks.voidFogBrightness
[Akashic Void Sky Fix] Patched org.blockartistry.mod.DynSurround.client.fog.BiomeFogColorCalculator -> VoidSkyHooks.voidFogBrightness
```

## Build e teste

```
./build.sh "<asm-debug-all-5.0.3.jar>:<launchwrapper-1.12.jar>:<forge-1.7.10-universal.jar>:<client-srg.jar>" <client-srg.jar> [DynamicSurroundings.jar]
```

`client-srg.jar` é o `client.jar` 1.7.10 da Mojang remapeado com SpecialSource e o `joined.srg`
do MCP 1.7.10. O `VoidSkyTransformerTest` aplica o transformer nas classes reais e confere três
coisas: que o hook ficou exatamente entre as instruções esperadas, que todos os métodos passam
no `BasicVerifier` do ASM, e que classes fora da lista não são alteradas.
