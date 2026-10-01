# ProtocolObelisk 1.9.16-EVOLUTION

Atualização da candidata 1.9.15, validada pelo usuário na admissão de clientes
ATM10 8.1. Esta versão corrige a comunicação dos seletores ProMenus e completa
a aplicação da normalização revisada na liberação de receitas.

## Componentes

- `ProtocolObelisk-Velocity-1.9.16-EVOLUTION.jar`: proxy e autoridade de rota.
- `ProtocolObelisk-Paper-1.9.16-EVOLUTION.jar`: lobby vanilla e proteção local.
- `integrations/ProMenus/`: dois menus operacionais com a verificação de
  disponibilidade antes da ação de conexão.
- Source completa, documentos de instalação, hashes e evidências de build.

Nenhum mod obrigatório no cliente, companion Youer ou companion Crucible novo.
O caminho ATM10 e o legado 1.7.10 continuam separados. Não há alteração de UUID,
online-mode, forwarding, servidores cadastrados ou política de autenticação.

## Correções

O filtro de registros do lobby preserva explicitamente `bungeecord:main`,
necessário para o transporte usado pelos seletores. O canal não vira um sink
modded e o processamento de pedidos de conexão continua nativo do Velocity.

A decisão de liberação de receitas utiliza a mesma identidade estrutural
normalizada já usada pelo mapa BlockState, configs e registries. A identidade
original continua nos registros de diagnóstico e no anúncio ao backend real.
Os comprovantes de entrega, o catálogo exato e os bootstraps continuam sendo
verificados antes de liberar receitas.

O Paper acrescenta observação limitada de mudanças nos controles de movimento
e teletransportes com `debug` habilitado. Não modifica física e não declara
resolvido o bug de pulo. Os detalhes e limites estão no README do módulo Paper.

## Estado da validação

A 1.9.15 recebeu validação real de entrada dos jogadores; essa evidência não
é uma homologação automática do novo binário 1.9.16. Consulte a auditoria desta
build para os testes efetivamente executados e seus limites.

Fingerprints, modlists e perfis continuam sem papel de ACL de admissão. A
normalização cobre somente equivalências revisadas; uma atualização futura do
modpack pode exigir novas evidências estruturais.

Instalação: `DEPLOY-EVOLUTION-1.9.16-ATM10-8.1.md`.
