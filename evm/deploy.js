const { BigNumber } = require("ethers");
const hre = require("hardhat");

async function main() {
  await deployTokens();
  await deployVault();
}

async function deployTokens() {
  const tokens = [
    ["USD Tethered", "USD"],
    ["GBP Tethered", "GBP"],
  ];

  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    const TestToken = await hre.ethers.getContractFactory("TestToken");
    const testToken = await TestToken.deploy(token[0], token[1]);

    await testToken.deployed();

    console.log(
      token[0] + " (" + token[1] + ") Token deployed to:",
      testToken.address
    );

    const accounts = await hre.ethers.getSigners();
    const owner = accounts[0];
    const share = (await testToken.totalSupply()).div(3);

    for (j = 1; j < 3; j++) {
      await testToken.transfer(accounts[j].address, share);
    }
  }
}

async function deployVault() {
  const SwapVault = await hre.ethers.getContractFactory("SwapVault");
  const swapVault = await SwapVault.deploy();

  await swapVault.deployed();

  console.log("SwapVault deployed to:", swapVault.address);
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
