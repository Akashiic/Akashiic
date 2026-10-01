# Evidências que motivaram a 1.9.16

A rodada de 04/09/2026 às 18:03–18:10 dos arquivos latest (65).log e
latest (66)(1).log confirmou entrada/autenticação de TNTDROIDA, pudim231,
Asht4roth e Akashiic. Segundo o usuário, os amigos não sofreram crash.

Os contratos observados 20ba732374e8... e d0d966c18445... receberam a identidade
estrutural revisada 9d06683c97b6..., o mapa BlockState e os recursos exatos.
A liberação de receitas, porém, ficou WITHHELD nos três amigos, enquanto
Akashiic recebeu RELEASE/READY. O código mostrou uma única decisão final
que ainda recebia o contrato original. Essa é a causa delimitada dessa diferença.

O Paper documentou cinco moved-too-quickly na nova rodada: três em pudim231,
um em Asht4roth e um em TNTDROIDA. Dois horários se alinham à autenticação;
TNTDROIDA também acumulou um resgate do vazio até QUIT. Isso não determina a
causa dos pulos. Os diagnósticos adicionados permanecem de observação.

Os menus/velocity.toml enviados depois confirmaram ação Bungee válida,
canal habilitado e destinos ATM10/Forbidden existentes. O filtro descartava
um anúncio isolado bungeecord:main. A reprodução isolada com o sanitizador da
1.9.15 e os 83 canais preservados da sessão Akashiic resultou em DROP; com
reserva explícita, PASS, mantendo um canal estranho excluído e o limite96.
A ordem nos dois menus também foi ajustada: condição antes da conexão.

A ausência de /server no log não comprova ausência de comando: o Velocity
fornecido tinha log-command-executions=false. Os anexos não contêm uma captura
decodificada do clique ou o binário ProMenus para um ensaio completo.

Não há nova prova de correção do Twilight Forest glacier nem de eliminação
do custo do JEI. As saídas às18:10:21 foram causadas pelo desligamento do proxy.
As evidências originais não são republicadas: não são necessárias à instalação.
