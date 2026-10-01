# Evolução técnica — 1.9.16

## Contrato de compatibilidade

`CARDINAL-POLICY.md` permanece a regra de admissão. A atualização seleciona
evidências estruturais revisadas para os clientes equivalentes; não cadastra
hashes de jogadores nem usa perfis como permissão de entrada.

## Transporte do seletor

O Velocity anuncia ao backend o canal BungeeCord em REGISTER. O filtro do
Obelisk agia sobre a cópia já destinada ao Paper e não preservava esse canal.
O teste isolado do sanitizador 1.9.15 reproduziu o descarte. Na nova versão,
o canal de transporte recebe espaço antes dos canais opcionais, conservando a
capacidade máxima de 96 e o tratamento nativo das mensagens pelo Velocity.

## Receitas

O catálogo de configs, o mapa BlockState e os registries já recebiam a chave
normalizada, porém a decisão final de receitas ainda recebia a chave original.
Isso produziu `WITHHELD` para os amigos apesar da entrega dos recursos exatos.
A atualização aplica a identidade estrutural também a essa decisão. Todos os
comprovantes exigidos continuam obrigatórios; identidade desconhecida não
autoriza dados inventados e tampouco vira motivo de expulsão.

## Movimentação

O diagnóstico anterior capturava JOIN, READY e QUIT. A próxima observação
precisa distinguir a transição de controles temporariamente bloqueados pela
autenticação de teletransportes e correções posteriores. A instrumentação
adicionada é limitada, opt-in e de leitura. A arquitetura não depende de uma
API específica do nLogin e não atribui o bug ao plugin por mera correlação.

## Evidência preservada

Recursos estruturais ATM10 8.1 permanecem herdados da 1.9.15/1.9.14. Não houve
novo boot/export NeoForge nesta atualização. Os binários de tradução são
comparados separadamente da versão de metadados dos plugins.
