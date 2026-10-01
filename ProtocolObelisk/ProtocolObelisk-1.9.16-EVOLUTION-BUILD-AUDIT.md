# Auditoria de build — 1.9.16-EVOLUTION

Status: PASS nos gates offline; candidata à homologação com clientes reais.

Base: 1.9.15-EVOLUTION, release SHA-256
`2fbde2e85e108e96569587eebd25a210ab5c4462c5f822f5f6c2f96a16d8b9a0`.

Método executado: Java21/JUnit direto, Netty real e descritores compile-only de
Paper/Velocity. Gradle não foi executado nesta rodada. Os descritores não
entram nos JARs publicados e não constituem prova de linkage no servidor real.

## Execuções efetivas

Duas compilações independentes em diretórios novos, `1.9.16-build-b` e
`1.9.16-build-c`, com o mesmo código congelado:

| Verificação | Resultado |
|---|---|
| Java21 `--release 21 -g -Xlint:all -Werror` | PASS: main e tests dos dois módulos, nas duas builds |
| JUnit Velocity com `-Xverify:all` | 512/512 em cada build; zero skip/failure/abort |
| JUnit Paper com `-Xverify:all` | 40/40 em cada build; zero skip/failure/abort |
| Testes Python do empacotador | 6/6 |
| Gate JAR: namespaces, versão, bytecode65, conteúdo/checksums | PASS: dois JARs em cada build |
| Comparação byte a byte dos JARs entre builds | PASS |
| Recursos comparados com a source 1.9.15 | Velocity 738/738 e Paper 3/3 idênticos |
| Menus comparados com o anexo original | Somente inversão das duas ações em cada menu |

São 552 testes Java distintos, mais 6 do empacotador. Não se multiplica a
contagem por repetir a suíte. Foram adicionados 28 testes Java nesta versão.
Classes main/test: Velocity 265/162; Paper 51/28.

SHA-256 dos JARs compilados:

- Paper (246.923 bytes): `d30284a81efe185c048c10b2bdc417c5b9c98dcb94835c19b372c85db9f18d37`.
- Velocity (18.472.708 bytes): `622118640d21268f17c01926ea2aa6f7a650f3b8b3da29bb6e6d16a414ffe1d6`.

O digest canônico de entrada das duas builds foi
`90a796a42260c104fac66725f5a51d62b632250e613e67d55d32d8d2a6191ea7`,
verificado antes/depois de cada compilação. Ele incluía documentação de
resultados ainda provisória. Após os testes, somente os cinco documentos de
auditoria/evidências foram fechados. Código, testes, recursos, menus e scripts
de build não foram alterados para publicar. O ZIP fonte final tem seu próprio
SHA-256 no CHECKSUMS.sha256.

A tentativa preliminar `1.9.16-build-a` passou 505 testes Velocity e falhou
nos sete novos testes da conclusão real de receitas: a configuração temporária
do teste deixava o serviço desativado. O teste passou a habilitar explicitamente
a ponte e a verificar essa precondição; nenhuma guarda de produção nem assertion
semântica foi removida. Depois, as duas builds completas acima passaram.
Nenhum resultado reprovado foi publicado e nenhum JAR antigo foi remarcado.

## Cobertura e limites

Os testes novos chamam o método real de conclusão da sessão, usam os catálogos
e comprovantes estruturais empacotados, e verificam liberação única e retenção
quando falta evidência. Os jogadores/servidores do harness são substitutos de
teste; não houve replay integral da negociação real ATM10 8.1.

A preservação Bungee é exercitada na configuração real do filtro e no
sanitizador, inclusive saturação do limite 96 e exclusão de canal estranho.
Anúncios e pins não podem reservar o transporte nativo como sink do Obelisk.
Não houve clique real no ProMenus nesta execução automatizada.

O Paper foi testado quanto a limites, expiração, observação de teleporte,
limpeza e isolamento de falhas. Não há alteração de física. A causa dos pulos
continua não demonstrada; o custo de JEI e os avisos glacier não são declarados
resolvidos. Também não houve novo boot/export NeoForge.

A aprovação offline não substitui a execução nos binários exatos de
Velocity/Paper e no cliente gráfico. A 1.9.15 teve entrada real confirmada pelo
usuário; a 1.9.16 ainda precisa confirmar primeira entrada, blocos/HUD,
inventário/receitas, clique no seletor e ida/volta ao lobby.
