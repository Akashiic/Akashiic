# ATM10 8.0 ProtocolObelisk profile generator (build-only)

Este código não é um plugin de runtime e não é incluído nos JARs do Velocity ou
Paper. Ele existe para auditar/regenerar o perfil versionado usando exclusivamente
o ServerFiles oficial ATM10 8.0 (CurseForge 8649107, SHA-256
`2150885deb54f97a63a17291b34706eaa713f2b0a5e31ac701c846383db306cc`),
NeoForge 21.1.247 e os codecs dos próprios mods.

O gerador deve ser compilado contra a instalação exata do pack, colocado
temporariamente em `mods/` e executado em uma cópia offline do server pack com:

```text
java -Datm10.sgFixtureOutput=/caminho/saida \
  @libraries/net/neoforged/neoforge/21.1.247/unix_args.txt nogui
```

Ao receber `ServerStartedEvent`, depois do carregamento dos datapacks, ele exporta
os três mapas Silent Gear, toda a transação de frozen registries, as extensões de
dynamic registries não cobertas pelo known pack vanilla, o pacote CONFIG padrão de
tags do registry `neovitae:sentient_upgrades`, os SERVER configs reais,
o mapa oficial do Apothic Enchanting, o bootstrap neutro do Mekanism e a tabela
numérica global de estados de bloco reconstruída pelo NeoForge. Cada codec é
decodificado até o fim e reencodado; contagens, limites e hashes são validados.
A publicação do diretório final é atômica e o servidor é encerrado inclusive em
caso de falha.

A versão 2.3.1 exige exatamente `minecraft:core:1.21.1`, enumera os IDs JSON
desse known pack e remove da extensão somente pares `registry/id` presentes
nele. O manifesto registra e hasheia o conjunto conhecido e cada omissão. Não
há filtro por namespace: IDs `minecraft:*` adicionados por mods permanecem na
extensão. Duplicatas, pack inesperado ou divergência de evidência abortam o
export.

O arquivo `dynamic-registry-tags.bin` é um corpo exato de
`ClientboundUpdateTagsPacket`, coletado do registry vivo depois dos datapacks. Ele
contém todas as tags de `neovitae:sentient_upgrades` e exige, no mínimo, as tags
não vazias `sentient_start`, `tooltip_order` e `trainer`, usadas pelas abas do
inventário NeoVitae. Não há criação de tag ou associação por aproximação.

`minecraft-block-states.tsv` liga cada ID numérico global de um estado
`minecraft:*` ao estado canônico completo (`namespace:block[property=value,...]`).
Estados de mods são deliberadamente excluídos porque um Paper vanilla não pode
emiti-los; seus slots ainda influenciam os IDs preservados nas linhas exportadas.
O arquivo é evidência build-only para comparar a paleta do Paper com a do cliente
exato; ele não é enviado na rede nem autoriza uma tradução aproximada.

O destino deve não existir. Execute dois boots independentes em destinos distintos
e compare recursivamente nomes, tamanhos e SHA-256 antes de integrar os bytes em
`silentgear-profiles/atm10-normal-8.0/`. Divergências devem ser enumeradas e
explicadas; nunca combine payloads de boots diferentes. Um perfil fixado a um
boot coerente pode ser usado somente em beta de smoke, com o desvio documentado,
e não pode ser promovido enquanto a compatibilidade real do lobby não o validar.

Este gerador pertence à cadeia histórica ATM10 8.0 e não é o exporter usado pela
1.9.14. Para a evidência atual, consulte
`tools/atm10-8.1-runtime-exporter/README.md` e
`build-evidence/1.9.14-EVOLUTION/ATM10-8.1-RUNTIME-EXPORT-AUDIT.md`. Não execute
nenhum gerador no proxy nem no lobby de produção, não reutilize um mundo de
produção e não distribua o server pack dentro da release do plugin.
