# Create: Power Grid — simplified server build

Unofficial NeoForge server build of [Create: Power Grid](https://github.com/patryk3211/PowerGrid) 0.6.2 for Minecraft 1.21.1. The server decides the electrical math. Joining players keep the original `powergrid` 0.6.2 jar.

Upstream commit: [`0938be06`](https://github.com/patryk3211/PowerGrid/commit/0938be067fb71f311b3e5fd6c5f7cdaed722e241). No new blocks, items, menus, or network packets.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62b47a)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1-1d1d1f)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](./LICENSE)

## Contents

- [Install](#install)
- [How power works](#how-power-works)
- [Generators](#generators)
- [Solar](#solar)
- [Other machines](#other-machines)
- [Build](#build)
- [Português](#português)
- [License](#license)

## Install

Put the server jar in the NeoForge server `mods` folder. Do not send it to clients.

Use `powergrid-mc1.21.1-0.6.2-0938be06.jar`. The file whose name ends in `-dev-shadow.jar` is not the one you install.

The published copy is attached to this repository's GitHub releases. Clients stay on the normal Power Grid 0.6.2 jar from the modpack.

## How power works

Sources on the same wire add together. Volts add and amps add. A gauge shows that line voltage.

A positive terminal tied to a negative terminal through a normal wire shorts that wire and cuts its loads. Other separate wires stay up. Nothing explodes from extra voltage.

Machines run at full strength when the line is at or above their rating and enough amps are left. A lower voltage runs them at that fraction. If the machines want more amps than the wire has, everything on that wire scales down together.

The multimeter's current mode reads the drop along the wire it is clamped to. A normal wire sits at the same voltage on both ends, so that reading stays at 0 A. The amps are still there for the machines.

## Generators

The clutch does not make power by itself. Full voltage is Create's shaft speed cap, 256 RPM on this pack. The generator's own overspeed limit is much higher and is not full power.

| Part | At 256 RPM |
| --- | --- |
| Normal induction rotor | 3120 V |
| Large induction rotor | 6240 V |
| Winding block within two blocks | 18 A |
| Conductive casing or generator housing within two blocks | 3 A |

Speed scales the voltage. Four normal rotors at 103 RPM are about 5020 V. Half speed is half voltage. Windings and casings do not add volts.

## Solar

Every solar panel block counts, including each panel on a bearing.

| Sun | One panel |
| --- | --- |
| Full sun | 36 V and 1.5 A |
| Shade | 36 V, amps fall with the light |
| Dark | 0 V and 0 A |

Several panels and several bearings on one wire stack. Three bearings do not stay at 36 V.

## Other machines

- A resistor drops volts by the amps passing through it times its ohms. With nothing drawing, the drop is 0.
- A transformer's output voltage is the input times secondary turns divided by primary turns. The amps go the other way.
- A variac does the same with its shaft.
- The basin heater is a 10 ohm coil. More volts make more heat.
- Lamps use their own ratings: 12 V, 120 V, and 240 V.
- Motors are 12 V and 4 A. Fans, pumps, alarms, electromagnets, and ordinary heaters are 12 V and 2 A.
- The FE inverter stays on its config voltage, 3000 V unless you changed it. The control wire turns it down. It spends 10 FE per watt actually delivered.
- The device connector turns the watts it draws into FE. Its cap is the line voltage times 2 FE each tick.

## Build

JDK 21 is required. From this folder:

```bat
gradlew.bat :forge:build -x sourcesJar -x remapSourcesJar
```

The installable jar is written to `forge\build\libs\powergrid-mc1.21.1-0.6.2-0938be06.jar`.

## Português

Versão de servidor, não oficial, do Create: Power Grid 0.6.2 para Minecraft 1.21.1. O servidor faz a conta. Quem entra no mundo continua com o jar original.

Instale só no servidor o arquivo `powergrid-mc1.21.1-0.6.2-0938be06.jar`. Não use o arquivo que termina em `-dev-shadow.jar`.

Fontes no mesmo fio somam volts e amperes. Ligar positivo com negativo derruba só aquele fio. Nada explode por sobra de tensão.

Um gerador com quatro rotores normais a 103 RPM fica em cerca de 5020 V. A 256 RPM, cada rotor normal vale 3120 V e cada rotor grande vale 6240 V. Cada bobina a até dois blocos soma 18 A. Cada carcaça condutiva ou carcaça de gerador nessa distância soma 3 A.

Cada bloco de painel solar, inclusive os de um bearing, dá 36 V e 1,5 A em sol pleno. Sombra corta a corrente. No escuro o painel fica em zero. Vários painéis no mesmo fio somam.

O modo de corrente do multímetro marca 0 A num fio comum, porque as duas pontas estão na mesma tensão. As máquinas ainda usam a corrente disponível.

## License

The upstream project is Apache-2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE). This folder is a modified server-side build, not the official mod.
